package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
	flagDomain      = flag.String("domain", "qe.example.com", "wildcard domain (e.g. qe.example.com → players connect to user.qe.example.com)")
	flagMCPort      = flag.String("mc-port", "25565", "port Minecraft players connect to")
	flagControlPort = flag.String("control-port", "7000", "port for mod control connections")
	flagTunnelPort  = flag.String("tunnel-port", "7001", "port for mod data tunnel connections")
	flagAPIPort     = flag.String("api-port", "7002", "port for HTTP status API")
)

// relay is the central state store.
type relay struct {
	mu       sync.RWMutex
	clients  map[string]*regClient    // username → registered mod client
	sessions map[string]chan net.Conn // sessionID → waiting tunnel conn
}

type regClient struct {
	username string
	players  int32 // atomic: active player connections
	mu       sync.Mutex
	w        *bufio.Writer
}

func (c *regClient) send(msg string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, err := fmt.Fprintln(c.w, msg); err != nil {
		return err
	}
	return c.w.Flush()
}

func newRelay() *relay {
	return &relay{
		clients:  make(map[string]*regClient),
		sessions: make(map[string]chan net.Conn),
	}
}

func main() {
	flag.Parse()
	r := newRelay()

	go r.serve(*flagControlPort, r.handleControl)
	go r.serve(*flagTunnelPort, r.handleTunnel)
	go r.serveAPI(*flagAPIPort)
	r.serve(*flagMCPort, r.handlePlayer)
}

func (r *relay) serve(port string, handler func(net.Conn)) {
	ln, err := net.Listen("tcp", ":"+port)
	if err != nil {
		log.Fatalf("listen :%s: %v", port, err)
	}
	log.Printf("listening on :%s", port)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept :%s: %v", port, err)
			continue
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			tc.SetNoDelay(true)
		}
		go handler(conn)
	}
}

// handleControl manages a mod's control connection.
// Protocol (line-delimited, UTF-8):
//
//	mod → relay: REGISTER <username> <lanPort>
//	relay → mod: OK <username>.<domain>
//	relay → mod: TUNNEL <sessionID>   (when a player connects)
//	relay → mod: PING                 (keepalive every 30 s)
//	mod → relay: PONG
func (r *relay) handleControl(conn net.Conn) {
	defer conn.Close()

	scanner := bufio.NewScanner(conn)
	w := bufio.NewWriter(conn)
	var username string

	// First message must be REGISTER
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	if !scanner.Scan() {
		return
	}
	conn.SetReadDeadline(time.Time{})

	parts := strings.Fields(scanner.Text())
	if len(parts) < 3 || parts[0] != "REGISTER" {
		return
	}
	requested := sanitize(parts[1])
	if requested == "" {
		return
	}

	// Resolve the slug to use.
	// Slugs with a hyphen are server-generated (word-word format).
	//   - Minecraft usernames cannot contain hyphens, so they never collide with
	//     player slots. We still Mojang-check as a safety net in case a future
	//     word list entry somehow matches a real username.
	//   - If taken or Mojang-blocked, fall back to a fresh random slug.
	// Plain slugs (no hyphen) are player usernames and accepted as-is.
	slug := requested
	if strings.Contains(slug, "-") {
		for isMojangUsername(slug) {
			log.Printf("slug %q matches a Mojang username, retrying", slug)
			slug = randomSlug()
		}
	}

	// Atomically claim the slug; if already registered pick a new random one.
	r.mu.Lock()
	for {
		if _, taken := r.clients[slug]; !taken {
			break
		}
		slug = randomSlug()
	}
	username = slug
	c := &regClient{username: username, w: w}
	r.clients[username] = c
	r.mu.Unlock()

	addr := username + "." + *flagDomain
	log.Printf("registered: %s (LAN port %s)", addr, parts[2])

	if err := c.send("OK " + addr); err != nil {
		r.deregister(username)
		return
	}

	// Keepalive: separate goroutine with its own quit signal.
	quit := make(chan struct{})
	go func() {
		tick := time.NewTicker(30 * time.Second)
		defer tick.Stop()
		for {
			select {
			case <-tick.C:
				if err := c.send("PING"); err != nil {
					return
				}
			case <-quit:
				return
			}
		}
	}()

	// Drain PONG / any future client→relay messages (currently only PONG)
	for scanner.Scan() {
		// ignore — PONG requires no action
	}

	close(quit)
	r.deregister(username)
	log.Printf("deregistered: %s", username)
}

func (r *relay) deregister(username string) {
	r.mu.Lock()
	delete(r.clients, username)
	r.mu.Unlock()
}

// handleTunnel receives a data tunnel connection opened by the mod.
// First line: TUNNEL <sessionID>
// After that, raw MC protocol is proxied.
func (r *relay) handleTunnel(conn net.Conn) {
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	sc := bufio.NewScanner(conn)
	if !sc.Scan() {
		conn.Close()
		return
	}
	conn.SetReadDeadline(time.Time{})

	parts := strings.Fields(sc.Text())
	if len(parts) != 2 || parts[0] != "TUNNEL" {
		conn.Close()
		return
	}
	sessionID := parts[1]

	r.mu.RLock()
	ch, ok := r.sessions[sessionID]
	r.mu.RUnlock()
	if !ok {
		log.Printf("unknown session: %s", sessionID)
		conn.Close()
		return
	}
	// Non-blocking send: if the session timed out, close immediately.
	select {
	case ch <- conn:
	default:
		log.Printf("session %s already closed, dropping tunnel conn", sessionID)
		conn.Close()
	}
}

// handlePlayer receives a connection from a remote Minecraft player.
// It reads the MC handshake packet to extract the target hostname,
// derives the username, signals the mod to open a tunnel, then splices.
// If no mod is registered for that username, it responds with an offline
// MOTD (for status pings) or a disconnect message (for login attempts).
func (r *relay) handlePlayer(conn net.Conn) {
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))

	rb := &recBuf{}

	// Packet length (varint)
	if _, err := rb.readVarInt(conn); err != nil {
		conn.Close()
		return
	}
	// Packet ID (varint, must be 0x00 for handshake)
	if _, err := rb.readVarInt(conn); err != nil {
		conn.Close()
		return
	}
	// Protocol version — captured for offline status response
	protoVer, err := rb.readVarInt(conn)
	if err != nil {
		conn.Close()
		return
	}
	// Server address string — used for routing
	hostname, err := rb.readString(conn)
	if err != nil {
		conn.Close()
		return
	}
	// Port (2 bytes)
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		conn.Close()
		return
	}
	rb.buf = append(rb.buf, portBytes...)
	// Next state — 1=status ping, 2=login
	nextState, err := rb.readVarInt(conn)
	if err != nil {
		conn.Close()
		return
	}

	conn.SetReadDeadline(time.Time{})

	// Strip FML markers ("host\x00FML\x00") and port suffix
	if idx := strings.IndexByte(hostname, 0); idx >= 0 {
		hostname = hostname[:idx]
	}
	hostname = strings.SplitN(hostname, ":", 2)[0]

	// "player123.qe.example.com" → "player123"
	username := strings.SplitN(hostname, ".", 2)[0]
	username = sanitize(username)

	r.mu.RLock()
	client, ok := r.clients[username]
	r.mu.RUnlock()

	if !ok {
		log.Printf("no client for %q (nextState=%d)", username, nextState)
		switch nextState {
		case 1:
			serveOfflineStatus(conn, username, protoVer)
		case 2:
			serveOfflineDisconnect(conn, username)
		default:
			conn.Close()
		}
		return
	}

	sessionID := randID()

	ch := make(chan net.Conn, 1)
	r.mu.Lock()
	r.sessions[sessionID] = ch
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.sessions, sessionID)
		r.mu.Unlock()
	}()

	if err := client.send("TUNNEL " + sessionID); err != nil {
		log.Printf("notify %s: %v", username, err)
		conn.Close()
		return
	}

	select {
	case tunnelConn := <-ch:
		// Replay the buffered handshake bytes so the LAN server sees the full packet
		if _, err := tunnelConn.Write(rb.buf); err != nil {
			conn.Close()
			tunnelConn.Close()
			return
		}
		atomic.AddInt32(&client.players, 1)
		splice(conn, tunnelConn)
		atomic.AddInt32(&client.players, -1)
	case <-time.After(15 * time.Second):
		log.Printf("tunnel timeout for %s", username)
		conn.Close()
		// Drain any tunnel conn that arrived just after the timeout.
		select {
		case late := <-ch:
			late.Close()
		default:
		}
	}
}

// serveOfflineStatus responds to a status ping for a user who isn't hosting.
// The server list entry shows a "not hosting" MOTD in red.
func serveOfflineStatus(conn net.Conn, username string, protoVer int) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	// Read and discard the Status Request packet (id=0x00, no payload).
	if err := drainPacket(conn); err != nil {
		return
	}

	var motdText string
	if strings.Contains(username, "-") {
		// word-word slug → dedicated server
		motdText = "§cThis server is currently offline."
	} else {
		// plain username → player's world
		motdText = "§c" + jsonEscape(ucFirst(username)) + " is not currently hosting a world!"
	}
	motd := fmt.Sprintf(
		`{"version":{"name":"QuickExpose","protocol":%d},"players":{"max":0,"online":0,"sample":[]},"description":{"text":"%s"}}`,
		protoVer, motdText)

	if err := writePacket(conn, 0x00, encodeString(motd)); err != nil {
		return
	}

	// Read Ping Request (id=0x01, 8-byte Int64 payload) and echo it back.
	pktLen, err := readRawVarInt(conn)
	if err != nil || pktLen < 9 {
		return
	}
	pktData := make([]byte, pktLen)
	if _, err := io.ReadFull(conn, pktData); err != nil {
		return
	}
	// pktData[0] = packet ID (0x01), pktData[1:9] = the ping value to echo
	_ = writePacket(conn, 0x01, pktData[1:])
}

// serveOfflineDisconnect sends a Login Disconnect for a user who isn't hosting.
func serveOfflineDisconnect(conn net.Conn, username string) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	// Read and discard the Login Start packet (we don't need the player name).
	_ = drainPacket(conn)

	var text string
	if strings.Contains(username, "-") {
		text = "§cThis server is currently offline."
	} else {
		text = "§c" + jsonEscape(ucFirst(username)) + " is not currently hosting a world!"
	}
	_ = writePacket(conn, 0x00, encodeString(`{"text":"`+text+`"}`))
}

// drainPacket reads and discards one complete length-prefixed MC packet.
func drainPacket(r io.Reader) error {
	n, err := readRawVarInt(r)
	if err != nil {
		return err
	}
	_, err = io.ReadFull(r, make([]byte, n))
	return err
}

// readRawVarInt reads a VarInt without recording the bytes.
func readRawVarInt(r io.Reader) (int, error) {
	var result, shift int
	var b [1]byte
	for {
		if _, err := r.Read(b[:]); err != nil {
			return 0, err
		}
		result |= int(b[0]&0x7F) << shift
		if b[0]&0x80 == 0 {
			return result, nil
		}
		shift += 7
		if shift >= 35 {
			return 0, fmt.Errorf("varint too large")
		}
	}
}

// encodeVarInt encodes an integer as a Minecraft VarInt.
func encodeVarInt(v int) []byte {
	var buf []byte
	for {
		if v&^0x7F == 0 {
			return append(buf, byte(v))
		}
		buf = append(buf, byte(v&0x7F)|0x80)
		v >>= 7
	}
}

// encodeString encodes a UTF-8 string with a VarInt length prefix.
func encodeString(s string) []byte {
	b := []byte(s)
	return append(encodeVarInt(len(b)), b...)
}

// writePacket writes a length-prefixed MC packet with the given ID and payload.
func writePacket(w io.Writer, id int, payload []byte) error {
	idBytes := encodeVarInt(id)
	if _, err := w.Write(encodeVarInt(len(idBytes) + len(payload))); err != nil {
		return err
	}
	if _, err := w.Write(idBytes); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

// ucFirst returns s with the first byte uppercased.
func ucFirst(s string) string {
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

// jsonEscape escapes backslashes and double-quotes for use inside a JSON string.
func jsonEscape(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	return s
}

// splice bidirectionally copies between two connections until either closes.
func splice(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	half := func(dst, src net.Conn) {
		defer wg.Done()
		io.Copy(dst, src)
		// Signal the other direction that we're done writing
		if tc, ok := dst.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
	}
	go half(a, b)
	go half(b, a)
	wg.Wait()
	a.Close()
	b.Close()
}

// recBuf reads bytes from a reader while recording everything it reads.
type recBuf struct {
	buf []byte
}

func (rb *recBuf) readVarInt(r io.Reader) (int, error) {
	var result, shift int
	var b [1]byte
	for {
		if _, err := r.Read(b[:]); err != nil {
			return 0, err
		}
		rb.buf = append(rb.buf, b[0])
		result |= int(b[0]&0x7F) << shift
		if b[0]&0x80 == 0 {
			return result, nil
		}
		shift += 7
		if shift >= 35 {
			return 0, fmt.Errorf("varint too large")
		}
	}
}

func (rb *recBuf) readString(r io.Reader) (string, error) {
	length, err := rb.readVarInt(r)
	if err != nil {
		return "", err
	}
	if length < 0 || length > 32767 {
		return "", fmt.Errorf("string length out of range: %d", length)
	}
	s := make([]byte, length)
	if _, err := io.ReadFull(r, s); err != nil {
		return "", err
	}
	rb.buf = append(rb.buf, s...)
	return string(s), nil
}

// sanitize keeps only alphanumeric, underscore, and hyphen characters, lowercased.
// Hyphens are allowed so that server slugs like "bright-wolf" survive intact.
func sanitize(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// isMojangUsername returns true if name is a registered Mojang/Microsoft account username.
// Used to prevent server word-word slugs from accidentally shadowing a real player's name.
func isMojangUsername(name string) bool {
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get("https://api.mojang.com/users/profiles/minecraft/" + name)
	if err != nil {
		return false // can't reach Mojang; assume safe
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, resp.Body)
	return resp.StatusCode == 200
}

var (
	slugAdjectives = []string{
		"amber", "azure", "brave", "bright", "calm", "clear", "cool", "dark",
		"deep", "fast", "fierce", "fresh", "golden", "green", "happy", "hidden",
		"iron", "jade", "kind", "large", "light", "loud", "lucky", "mighty",
		"misty", "mystic", "noble", "north", "proud", "quick", "quiet", "rapid",
		"red", "rough", "royal", "silent", "silver", "sleek", "smooth", "solar",
		"still", "stone", "strong", "swift", "tall", "teal", "tiny", "tough",
		"vivid", "warm", "wild", "wise",
	}
	slugNouns = []string{
		"arrow", "badger", "beacon", "bear", "blade", "bolt", "brook", "cave",
		"cliff", "cloud", "comet", "creek", "dawn", "deer", "dune", "eagle",
		"ember", "falcon", "fern", "flame", "flash", "fox", "frost", "gate",
		"glacier", "grove", "hawk", "hill", "hollow", "isle", "lake", "leaf",
		"lion", "marsh", "mist", "moon", "moss", "oak", "peak", "pine",
		"pond", "raven", "reef", "ridge", "river", "rock", "rose", "sage",
		"shadow", "shore", "sky", "snow", "spark", "star", "storm", "stream",
		"tiger", "vale", "vine", "wave", "wind", "wolf", "wood",
	}
)

func randomSlug() string {
	return slugAdjectives[rand.Intn(len(slugAdjectives))] + "-" + slugNouns[rand.Intn(len(slugNouns))]
}

func randID() string {
	const chars = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, 16)
	for i := range b {
		b[i] = chars[rand.Intn(len(chars))]
	}
	return string(b)
}

// serveAPI runs an HTTP server that exposes slug status for the website.
func (r *relay) serveAPI(port string) {
	mux := http.NewServeMux()
	mux.HandleFunc("/status", r.handleStatusAPI)
	log.Printf("HTTP API listening on :%s", port)
	srv := &http.Server{
		Addr:         ":" + port,
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 15 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Printf("API server: %v", err)
	}
}

type statusResult struct {
	Slug               string `json:"slug"`
	Valid              bool   `json:"valid"`
	Type               string `json:"type"` // "server" or "player"
	Online             bool   `json:"online"`
	Players            int32  `json:"players"`
	AvailableForServer bool   `json:"availableForServer"`
	IsRealPlayer       bool   `json:"isRealPlayer"`
	MojangConflict     bool   `json:"mojangConflict"`
}

func (r *relay) handleStatusAPI(w http.ResponseWriter, req *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "*")
	w.Header().Set("Content-Type", "application/json")

	if req.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	slug := sanitize(req.URL.Query().Get("slug"))
	if slug == "" {
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprint(w, `{"error":"missing slug"}`)
		return
	}

	isServer := strings.Contains(slug, "-")

	r.mu.RLock()
	client, online := r.clients[slug]
	var players int32
	if online {
		players = atomic.LoadInt32(&client.players)
	}
	r.mu.RUnlock()

	var isRealPlayer, mojangConflict bool
	if isServer {
		if !online {
			mojangConflict = isMojangUsername(slug)
		}
	} else {
		isRealPlayer = isMojangUsername(slug)
	}

	slugType := "player"
	if isServer {
		slugType = "server"
	}

	res := statusResult{
		Slug:               slug,
		Valid:              true,
		Type:               slugType,
		Online:             online,
		Players:            players,
		AvailableForServer: isServer && !online && !mojangConflict,
		IsRealPlayer:       isRealPlayer,
		MojangConflict:     mojangConflict,
	}

	json.NewEncoder(w).Encode(res)
}
