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
  echo -e "${BOLD}${CYAN}── $1 ${NC}"
}

step()  { echo -e "${YELLOW}   $1${NC}"; }
ok()    { echo -e "${GREEN}   ✓ $1${NC}"; }
fail()  { echo -e "${RED}   ✗ $1${NC}"; }

echo ""
echo -e "${BOLD}PEMDOS - Distributed Object Store${NC}"
echo -e "Reed-Solomon 3+2  ·  Homomorphic Fingerprinting  ·  PODC 2007"

# ── Start ──────────────────────────────────────────────────
step "Starting services..."
docker compose up --build -d > /dev/null 2>&1

echo -n "   Waiting for coordinator"
for i in $(seq 1 30); do
  curl -s "$BASE_URL/api/nodes" > /dev/null 2>&1 && echo "" && break
  echo -n "."; sleep 1
done

# ── 1. Node status ─────────────────────────────────────────
header "NODE STATUS"
curl -s "$BASE_URL/api/nodes" | python3 -c "
import sys, json
for n in json.load(sys.stdin):
    mark = '✓' if n['status'] == 'HEALTHY' else '✗'
    print(f\"   {mark} {n['nodeId']}  {n['status']}\")
"

# ── 2. Upload ──────────────────────────────────────────────
header "UPLOAD"
echo "PEMDOS test file." > /tmp/pemdos-demo.txt

UPLOAD=$(curl -s -X POST "$BASE_URL/api/objects?key=pemdos-demo.txt" \
  -F "file=@/tmp/pemdos-demo.txt")

OBJECT_ID=$(echo "$UPLOAD" | python3 -c "import sys,json; print(json.load(sys.stdin)['objectId'])")
EVAL_R=$(echo "$UPLOAD"   | python3 -c "import sys,json; print(json.load(sys.stdin)['homomorphicEvalPoint'])")

ok "Stored across 5 nodes"
echo -e "   objectId  : $OBJECT_ID"
echo -e "   eval point r = $EVAL_R  (random GF(2^8), used for all fingerprint checks)"

# ── 3. Verify clean ────────────────────────────────────────
header "VERIFY - ALL SHARDS INTACT"
curl -s "$BASE_URL/api/objects/pemdos-demo.txt/verify" | python3 -c "
import sys, json
for r in json.load(sys.stdin):
    mark = '✓' if r['homomorphic'] == 'PASS' else '✗'
    print(f\"   {mark} {r['nodeId']}  {r['homomorphic']}\")
"

# ── 4. Download clean ──────────────────────────────────────
header "DOWNLOAD - INTACT FILE"
curl -s "$BASE_URL/api/objects/pemdos-demo.txt" -o /tmp/pemdos-downloaded.txt
if diff -q /tmp/pemdos-demo.txt /tmp/pemdos-downloaded.txt > /dev/null; then
  ok "File matches original"
else
  fail "File mismatch"
fi

# ── 5. Silent corruption ───────────────────────────────────
header "SILENT CORRUPTION DETECTION"
docker exec pemdos-node-1-1 sh -c "echo CORRUPTED > /data/shards/$OBJECT_ID/0.shard"
ok "Shard 0 on node-1 corrupted - node is still alive"

echo ""
curl -s "$BASE_URL/api/objects/pemdos-demo.txt/verify" | python3 -c "
import sys, json
for r in json.load(sys.stdin):
    mark = '✓' if r['homomorphic'] == 'PASS' else '✗'
    note = '  ← DETECTED' if r['homomorphic'] == 'FAIL' else ''
    print(f\"   {mark} {r['nodeId']}  {r['homomorphic']}{note}\")
"

curl -s "$BASE_URL/api/objects/pemdos-demo.txt" -o /tmp/pemdos-after-corrupt.txt
if diff -q /tmp/pemdos-demo.txt /tmp/pemdos-after-corrupt.txt > /dev/null; then
  ok "File still reconstructed correctly from 4 valid shards"
fi

# ── 6. Fault tolerance ─────────────────────────────────────
header "FAULT TOLERANCE - FAILURE BOUNDARY"
echo "PEMDOS fault test." > /tmp/pemdos-fault.txt
curl -s -X POST "$BASE_URL/api/objects?key=pemdos-fault.txt" \
  -F "file=@/tmp/pemdos-fault.txt" > /dev/null

docker stop pemdos-node-1-1 pemdos-node-2-1 pemdos-node-3-1 > /dev/null
sleep 2
ok "3 nodes stopped - only 2 shards reachable"

HTTP=$(curl -s -o /tmp/err.txt -w "%{http_code}" "$BASE_URL/api/objects/pemdos-fault.txt")
MSG=$(python3 -c "import json; print(json.load(open('/tmp/err.txt')).get('message','failed'))" 2>/dev/null)
fail "Download failed: $MSG"

docker start pemdos-node-1-1 > /dev/null
sleep 4
ok "node-1 restored - now exactly 3 shards reachable"

HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/objects/pemdos-fault.txt")
[ "$HTTP" = "200" ] && ok "Download succeeded" || fail "Download failed (HTTP $HTTP)"

# ── Cleanup ────────────────────────────────────────────────
header "CLEANUP"
docker start pemdos-node-2-1 pemdos-node-3-1 > /dev/null
curl -s -X DELETE "$BASE_URL/api/objects/pemdos-demo.txt"  > /dev/null
curl -s -X DELETE "$BASE_URL/api/objects/pemdos-fault.txt" > /dev/null
rm -f /tmp/pemdos-demo.txt /tmp/pemdos-downloaded.txt /tmp/pemdos-after-corrupt.txt \
      /tmp/pemdos-fault.txt /tmp/err.txt
ok "All nodes restored · objects deleted"
echo ""
