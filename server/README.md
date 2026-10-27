# QuickExpose Relay Server

A tiny Go binary that reverse-tunnels Minecraft LAN sessions to a public URL.
No external dependencies — compiles to a single static binary.

## How it works

```
Player → username.qe.example.com:25565
           │
    Relay server (this binary)
    ├─ reads MC handshake → extracts "username"
    ├─ tells the mod: TUNNEL <id>    (via control connection)
    └─ splices: player ↔ tunnel ↔ localhost:<lanPort>
           │
       Minecraft mod (in the LAN host's game)
       opens a new TCP conn → relay:7001, replies TUNNEL <id>
       splices: relay ↔ localhost:<lanPort>
```

## Build

```bash
cd server
go build -o quickexpose-relay .
```

For a portable Linux binary from macOS/Windows:

```bash
GOOS=linux GOARCH=amd64 go build -o quickexpose-relay-linux .
```

## Run

```bash
./quickexpose-relay \
  -domain    qe.example.com \   # your wildcard domain
  -mc-port   25565 \            # port players connect to (Minecraft default)
  -control-port 7000 \          # mod ↔ relay control channel
  -tunnel-port  7001            # mod ↔ relay data tunnels
```

## DNS / firewall

1. Add a wildcard DNS A record: `*.qe.example.com → <server IP>`
2. Open ports **25565**, **7000**, and **7001** (TCP) in your firewall/security group.

## nginx (optional, only needed if you share port 25565 with other services)

If something else already runs on port 25565, put nginx in front as a stream proxy:

```nginx
stream {
    server {
        listen 25565;
        proxy_pass 127.0.0.1:25565;  # relay's mc-port on localhost
    }
}
```

Run the relay on a non-privileged port and nginx on 25565.

## systemd service

```ini
[Unit]
Description=QuickExpose relay
After=network.target

[Service]
ExecStart=/opt/quickexpose/quickexpose-relay -domain qe.example.com
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Configuring the mod

Edit `src/main/java/cc/kokodev/qe/QuickExposeConfig.java` and set:

```java
public static final String RELAY_HOST = "qe.example.com";  // your server
public static final int    CONTROL_PORT = 7000;
public static final int    TUNNEL_PORT  = 7001;
```

Then rebuild the mod with `./gradlew build`.
