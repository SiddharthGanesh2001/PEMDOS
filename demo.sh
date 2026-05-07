#!/bin/bash

set -e

BASE_URL="http://localhost:8080"

BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

header() {
  echo ""
  echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}${CYAN}  $1${NC}"
  echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

step()  { echo -e "\n${YELLOW}▶ $1${NC}"; }
ok()    { echo -e "${GREEN}  ✓ $1${NC}"; }
fail()  { echo -e "${RED}  ✗ $1${NC}"; }

wait_for_coordinator() {
  echo -n "  Waiting for coordinator to start"
  for i in $(seq 1 30); do
    if curl -s "$BASE_URL/api/nodes" > /dev/null 2>&1; then
      echo ""
      return 0
    fi
    echo -n "."
    sleep 1
  done
  echo ""
  echo "  Coordinator did not start - check docker logs"
  exit 1
}

# ─────────────────────────────────────────────────────────
header "PEMDOS - Distributed Object Store Demo"
echo "  Reed-Solomon 3+2 · Homomorphic Fingerprinting"
echo "  Hendricks, Ganger, Reiter - PODC 2007"
# ─────────────────────────────────────────────────────────

step "Building and starting all services (first run compiles inside Docker)..."
docker compose up --build -d 2>/dev/null
wait_for_coordinator
ok "All services running"

# ─────────────────────────────────────────────────────────
header "1. NODE STATUS"
# ─────────────────────────────────────────────────────────

curl -s "$BASE_URL/api/nodes" | python3 -c "
import sys, json
for n in json.load(sys.stdin):
    mark = '✓' if n['status'] == 'HEALTHY' else '✗'
    print(f\"  {mark} {n['nodeId']:8}  :{n['port']}  {n['status']}\")
"

# ─────────────────────────────────────────────────────────
header "2. UPLOAD"
# ─────────────────────────────────────────────────────────

echo "PEMDOS demo file - Reed-Solomon erasure coding with homomorphic fingerprint verification." \
  > /tmp/pemdos-demo.txt

step "Uploading pemdos-demo.txt..."
UPLOAD=$(curl -s -X POST "$BASE_URL/api/objects?key=pemdos-demo.txt" \
  -F "file=@/tmp/pemdos-demo.txt")

OBJECT_ID=$(echo "$UPLOAD" | python3 -c "import sys,json; print(json.load(sys.stdin)['objectId'])")
EVAL_R=$(echo "$UPLOAD"   | python3 -c "import sys,json; print(json.load(sys.stdin)['homomorphicEvalPoint'])")

ok "Stored - objectId: $OBJECT_ID"
echo "  Eval point r = $EVAL_R  (random GF(2^8) element, used for all fingerprint checks)"

echo ""
echo "$UPLOAD" | python3 -c "
import sys, json
for s in json.load(sys.stdin)['shards']:
    kind = 'parity' if s['parity'] else 'data  '
    print(f\"  shard {s['shardIndex']}  {kind}  → {s['nodeId']}\")
"

# ─────────────────────────────────────────────────────────
header "3. VERIFY - ALL SHARDS INTACT"
# ─────────────────────────────────────────────────────────

step "Running homomorphic fingerprint check on all 5 shards..."
curl -s "$BASE_URL/api/objects/pemdos-demo.txt/verify" | python3 -c "
import sys, json
for r in json.load(sys.stdin):
    mark = '✓' if r['homomorphic'] == 'PASS' else '✗'
    print(f\"  {mark} {r['nodeId']:8}  homomorphic: {r['homomorphic']}\")
"

# ─────────────────────────────────────────────────────────
header "4. DOWNLOAD - INTACT FILE"
# ─────────────────────────────────────────────────────────

step "Downloading pemdos-demo.txt..."
curl -s "$BASE_URL/api/objects/pemdos-demo.txt" -o /tmp/pemdos-downloaded.txt
if diff -q /tmp/pemdos-demo.txt /tmp/pemdos-downloaded.txt > /dev/null; then
  ok "Downloaded file matches original exactly"
else
  fail "File mismatch"
fi

# ─────────────────────────────────────────────────────────
header "5. SILENT CORRUPTION DETECTION"
# ─────────────────────────────────────────────────────────

step "Corrupting shard 0 on node-1 (node remains alive and responsive)..."
docker exec pemdos-node-1-1 sh -c "echo CORRUPTED > /data/shards/$OBJECT_ID/0.shard"
ok "Shard overwritten - node-1 is still running"

step "Verifying after corruption..."
curl -s "$BASE_URL/api/objects/pemdos-demo.txt/verify" | python3 -c "
import sys, json
for r in json.load(sys.stdin):
    mark = '✓' if r['homomorphic'] == 'PASS' else '✗'
    note = '  ← CORRUPTION DETECTED' if r['homomorphic'] == 'FAIL' else ''
    print(f\"  {mark} {r['nodeId']:8}  homomorphic: {r['homomorphic']}{note}\")
"

step "Downloading despite corruption (coordinator skips node-1, reconstructs from 4 valid shards)..."
curl -s "$BASE_URL/api/objects/pemdos-demo.txt" -o /tmp/pemdos-after-corruption.txt
if diff -q /tmp/pemdos-demo.txt /tmp/pemdos-after-corruption.txt > /dev/null; then
  ok "File reconstructed correctly - fault tolerance absorbs 1 bad shard"
else
  fail "Reconstruction failed"
fi

# ─────────────────────────────────────────────────────────
header "6. FAULT TOLERANCE - FAILURE BOUNDARY"
# ─────────────────────────────────────────────────────────

step "Uploading a second clean file for the fault tolerance test..."
echo "Fault tolerance test file." > /tmp/pemdos-fault.txt
curl -s -X POST "$BASE_URL/api/objects?key=pemdos-fault.txt" \
  -F "file=@/tmp/pemdos-fault.txt" > /dev/null
ok "pemdos-fault.txt uploaded across all 5 nodes"

step "Stopping 3 nodes (only 2 shards will be reachable - below the threshold of 3)..."
docker stop pemdos-node-1-1 pemdos-node-2-1 pemdos-node-3-1 > /dev/null
sleep 2

curl -s "$BASE_URL/api/nodes" | python3 -c "
import sys, json
for n in json.load(sys.stdin):
    mark = '✓' if n['status'] == 'HEALTHY' else '✗'
    print(f\"  {mark} {n['nodeId']:8}  {n['status']}\")
"

step "Attempting download with 3 nodes down..."
HTTP=$(curl -s -o /tmp/pemdos-fail-body.txt -w "%{http_code}" "$BASE_URL/api/objects/pemdos-fault.txt")
if [ "$HTTP" != "200" ]; then
  MSG=$(python3 -c "import sys,json; print(json.load(open('/tmp/pemdos-fail-body.txt')).get('message','failed'))" 2>/dev/null || echo "failed")
  fail "Download failed (HTTP $HTTP): $MSG"
else
  echo "  unexpected success"
fi

step "Bringing one node back (now exactly 3 shards reachable - the minimum)..."
docker start pemdos-node-1-1 > /dev/null
sleep 4
ok "node-1 restarted"

step "Attempting download again..."
HTTP=$(curl -s -o /tmp/pemdos-recovered.txt -w "%{http_code}" "$BASE_URL/api/objects/pemdos-fault.txt")
if [ "$HTTP" = "200" ]; then
  ok "Download succeeded - reconstructed from exactly 3 shards"
else
  fail "Download failed (HTTP $HTTP)"
fi

# ─────────────────────────────────────────────────────────
header "CLEANUP"
# ─────────────────────────────────────────────────────────

step "Restoring stopped nodes..."
docker start pemdos-node-2-1 pemdos-node-3-1 > /dev/null
ok "All nodes restored"

step "Deleting demo objects..."
curl -s -X DELETE "$BASE_URL/api/objects/pemdos-demo.txt"  > /dev/null
curl -s -X DELETE "$BASE_URL/api/objects/pemdos-fault.txt" > /dev/null
ok "Objects deleted"

rm -f /tmp/pemdos-demo.txt /tmp/pemdos-downloaded.txt /tmp/pemdos-after-corruption.txt \
      /tmp/pemdos-fault.txt /tmp/pemdos-fail-body.txt /tmp/pemdos-recovered.txt

echo ""
echo -e "${BOLD}  Demo complete.${NC}"
echo ""
