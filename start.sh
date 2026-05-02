#!/usr/bin/env bash
set -euo pipefail

# ── helpers ──────────────────────────────────────────────────────────────────

info()  { echo "  $*"; }
ok()    { echo "✅  $*"; }
warn()  { echo "⚠️   $*"; }
die()   { echo "❌  $*" >&2; exit 1; }

echo ""
echo "  LLM Sidecar — quick start"
echo "  ──────────────────────────"
echo ""

# ── check Docker ─────────────────────────────────────────────────────────────

if ! docker info &>/dev/null; then
  die "Docker is not running. Start Docker Desktop and try again."
fi
ok "Docker is running"

# ── check / create .env ──────────────────────────────────────────────────────

if [ ! -f .env ]; then
  cp .env.example .env
  warn ".env not found — created from .env.example"
  warn "Edit .env and add your API keys, then run this script again."
  echo ""
  echo "  nano .env   # or open in your editor"
  echo ""
  exit 1
fi
ok ".env found"

# Warn if keys look like placeholders
if grep -qE "^OPENAI_API_KEY=sk-\.\.\." .env 2>/dev/null; then
  die "OPENAI_API_KEY in .env still has the placeholder value. Add your real key."
fi
if ! grep -qE "^OPENAI_API_KEY=.{10,}" .env 2>/dev/null; then
  warn "OPENAI_API_KEY appears to be missing or very short in .env"
fi
ok "API keys look set"

# ── build + start ─────────────────────────────────────────────────────────────

echo ""
info "Building images and starting services…"
echo ""

docker compose up --build -d

# ── wait for healthy ──────────────────────────────────────────────────────────

echo ""
info "Waiting for sidecar health check…"

for i in $(seq 1 20); do
  if curl -sf http://localhost:8080/health >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

HEALTH=$(curl -sf http://localhost:8080/health 2>/dev/null || echo "unavailable")

echo ""
ok "Sidecar:   http://localhost:8080   →  $HEALTH"
ok "Gradio UI: http://localhost:7860"
echo ""
echo "  To follow logs:  docker compose logs -f"
echo "  To stop:         docker compose down"
echo ""
