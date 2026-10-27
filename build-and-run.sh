#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

# ── colours ────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
step()  { echo -e "\n${BOLD}${CYAN}▶ $*${NC}"; }
ok()    { echo -e "${GREEN}✓ $*${NC}"; }
die()   { echo -e "${RED}✗ $*${NC}" >&2; exit 1; }

# ── args ───────────────────────────────────────────────────────────────────
BUILD_MODS=true
DEPLOY=false

for arg in "$@"; do
  case "$arg" in
    --no-mods)   BUILD_MODS=false ;;
    --deploy)    DEPLOY=true ;;
    --help|-h)
      echo "Usage: $0 [--no-mods] [--deploy]"
      echo "  --no-mods   skip Gradle mod builds"
      echo "  --deploy    scp linux/arm64 binary to server and restart service"
      exit 0 ;;
    *) die "Unknown argument: $arg" ;;
  esac
done

# ── relay ──────────────────────────────────────────────────────────────────
step "Building relay (native)"
cd "$ROOT/server"
go build -o relay . || die "Go build failed"
ok "server/relay built"

step "Building relay (linux/arm64 for deployment)"
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o quickexpose-relay-linux-arm64 . || die "Cross-compile failed"
ok "server/quickexpose-relay-linux-arm64 built"

step "Building relay (linux/amd64 for deployment)"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o quickexpose-relay-linux-amd64 . || die "Cross-compile failed"
ok "server/quickexpose-relay-linux-amd64 built"
cd "$ROOT"

# ── mods ───────────────────────────────────────────────────────────────────
if $BUILD_MODS; then
  jar_name() { basename "$(ls "$1"/build/libs/*.jar 2>/dev/null | grep -v '\-sources\.' | grep -v '\-dev\.' | head -1)"; }

  step "Building mod — 1.21.1"
  ./gradlew build -q || die "1.21.1 build failed"
  ok "build/libs/$(jar_name .)"

  step "Building mod — 1.21.11"
  (cd versions/1.21.11 && ../../gradlew build -q) || die "1.21.11 build failed"
  ok "versions/1.21.11/build/libs/$(jar_name versions/1.21.11)"

  step "Building mod — 26.2"
  (cd versions/26.2 && ../../gradlew build -q) || die "26.2 build failed"
  ok "versions/26.2/build/libs/$(jar_name versions/26.2)"

  step "Building mod — 26.3"
  (cd versions/26.3 && ../../gradlew build -q) || die "26.3 build failed"
  ok "versions/26.3/build/libs/$(jar_name versions/26.3)"
fi

# ── deploy ─────────────────────────────────────────────────────────────────
if $DEPLOY; then
  REMOTE="${DEPLOY_HOST:-ubuntu@qe.kokodev.cc}"
  REMOTE_PATH="${DEPLOY_PATH:-/home/ubuntu/quickexpose-relay-linux-arm64}"
  SERVICE="${DEPLOY_SERVICE:-quickexpose-relay.service}"

  step "Deploying to $REMOTE"
  scp "$ROOT/server/quickexpose-relay-linux-arm64" "$REMOTE:$REMOTE_PATH" || die "scp failed"
  ssh "$REMOTE" "sudo systemctl restart $SERVICE && sudo systemctl status $SERVICE --no-pager" \
    || die "Restart failed"
  ok "Deployed and restarted $SERVICE"
fi

