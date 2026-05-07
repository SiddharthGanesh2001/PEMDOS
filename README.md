# PEMDOS

**Persistent Erasure-coded Modular Distributed Object Store**

A distributed object storage system implementing Reed-Solomon erasure coding with homomorphic fingerprint verification, based on the paper *"Verifying Distributed Erasure-Coded Data"* - Hendricks, Ganger, Reiter (PODC 2007).

---

## Submission contents (CS 588)

| File | Purpose |
|---|---|
| `README.md` | This file - how to build, run, and verify the system |
| `PEMDOS-Report.pdf` | Comprehensive project report (introduction, design, implementation, screenshots, references) |
| `demo-clean.sh` | **Recommended** automated test script - concise per-step output |
| `demo.sh` | Verbose alternative test script (same coverage, more detail per step) |
| `docker-compose.yml` | One-command orchestration of postgres + 5 nodes + coordinator |
| `common/`, `coordinator/`, `storage-node/` | Source code (three Gradle submodules) |

---

## What it does

Upload a file → it gets split into 5 shards across 5 independent storage nodes. Any 3 of those 5 shards are enough to reconstruct the original file. The system tolerates up to 2 simultaneous node failures or silent data corruptions.

---

## Architecture

```
                        ┌──────────────┐
                        │    CLIENT    │
                        └──────┬───────┘
                               │ REST / HTTP
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                        COORDINATOR  :8080                    │
│                                                              │
│  ┌─────────────────────┐   ┌─────────────────────────────┐   │
│  │  ObjectStoreService │   │  Fingerprint Verification   │   │
│  │  · encode shards    │   │  · SHA-256  (integrity)     │   │
│  │  · distribute       │   │  · Homomorphic (consistency)│   │
│  │  · reconstruct      │   └─────────────────────────────┘   │
│  └─────────────────────┘                                     │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐   │
│  │                   PostgreSQL  :5432                   │   │
│  │      stored_objects · shard_metadata · storage_nodes  │   │
│  └───────────────────────────────────────────────────────┘   │
└────┬──────────┬──────────┬──────────┬──────────┬─────────────┘
     │  gRPC    │  gRPC    │  gRPC    │  gRPC    │  gRPC
     ▼          ▼          ▼          ▼          ▼
┌─────────┐┌─────────┐┌─────────┐┌─────────┐┌─────────┐
│ node-1  ││ node-2  ││ node-3  ││ node-4  ││ node-5  │
│  :5001  ││  :5002  ││  :5003  ││  :5004  ││  :5005  │
├─────────┤├─────────┤├─────────┤├─────────┤├─────────┤
│ shard 0 ││ shard 1 ││ shard 2 ││ shard 3 ││ shard 4 │
│  data   ││  data   ││  data   ││ parity  ││ parity  │
└─────────┘└─────────┘└─────────┘└─────────┘└─────────┘
```

**Upload flow - how a file becomes 5 shards:**
```
  ┌─────────────┐   Reed-Solomon    ┌──────┬──────┬──────┬──────┬──────┐
  │   file.txt  │  encode (3+2)     │  D0  │  D1  │  D2  │  P3  │  P4  │
  │  any size   │ ────────────────▶ │ data │ data │ data │ par  │ par  │
  └─────────────┘                   └──┬───┴──┬───┴──┬───┴──┬───┴──┬───┘
                                       │      │      │      │      │
                                    node-1 node-2 node-3 node-4 node-5
```

**Fault tolerance - any 3 of 5 shards reconstruct the file:**
```
  ● node-1  D0  ✓               ┐
  ✕ node-2  D1  ✗  (dead)       │  only 3 needed
  ● node-3  D2  ✓               ├─────────────────▶  file.txt  ✓
  ✕ node-4  P3  ✗  (dead)       │
  ● node-5  P4  ✓               ┘
```

**Three Gradle submodules:**

| Module | Role |
|---|---|
| `common` | Shared library: Reed-Solomon codec, Galois field, fingerprint services, protobuf definitions |
| `coordinator` | REST API, metadata in PostgreSQL, distributes shards to nodes over gRPC |
| `storage-node` | gRPC server, stores shard files on disk |

---

## Verification - two layers

### Layer 1: SHA-256
Each shard's SHA-256 hash is stored in the coordinator at upload time. On download, the hash is recomputed and compared. Detects bit-level corruption.

### Layer 2: Homomorphic Fingerprinting
Based on Hendricks/Ganger/Reiter PODC 2007.

A random evaluation point `r` is chosen from GF(2^8) at upload time. Each shard is treated as a polynomial and evaluated at `r`:

```
fp(shard) = shard[0] + shard[1]·r + shard[2]·r² + ...
```

Because Reed-Solomon encoding is **linear over GF(2^8)**, the fingerprint of any encoded shard must equal a specific linear combination (defined by the encoding matrix) of the data shard fingerprints:

```
fp(shard_j) = M[j][0]·fp(D0) + M[j][1]·fp(D1) + M[j][2]·fp(D2)
```

This verifies that a shard is **consistent with the original encoding** - not just internally intact. A node cannot fake a valid shard without knowing the relationship between all shards.

---

## Reed-Solomon parameters

| Parameter | Value |
|---|---|
| Data shards | 3 |
| Parity shards | 2 |
| Total nodes | 5 |
| Fault tolerance | Any 2 nodes can fail or be corrupt |
| Storage overhead | 1.67× (vs 3× for replication) |

---

## Running and testing

**Prerequisite:** Docker Desktop. **No Java, no Gradle, no other dependencies required on the host** - the entire build runs inside Docker via a multi-stage Dockerfile.

### One-command end-to-end test (recommended)

The script handles everything: builds the project inside Docker, starts all 7 containers (postgres + 5 nodes + coordinator), runs every functional test (upload, verify, download, silent corruption detection, fault tolerance boundary), and cleans up afterward.

```bash
./demo-clean.sh
```

`demo.sh` is an alternative version with more verbose, narrated output - same test coverage.

### Running the system manually

```bash
docker compose up --build         # build and start everything
```

- **Dashboard:** http://localhost:8080
- **Swagger UI:** http://localhost:8080/swagger-ui.html

---

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/objects?key={key}` | Upload a file (multipart) |
| `GET` | `/api/objects/{key}` | Download a file |
| `GET` | `/api/objects/{key}/metadata` | Object metadata + shard info |
| `GET` | `/api/objects/{key}/verify` | Per-shard homomorphic fingerprint report |
| `DELETE` | `/api/objects/{key}` | Delete an object |
| `GET` | `/api/objects` | List all objects |
| `GET` | `/api/nodes` | Live status of all storage nodes |

---

## Demo: silent corruption detection

```bash
# 1. Upload a file
curl -X POST "http://localhost:8080/api/objects?key=demo.txt" -F "file=@demo.txt"
# Note the objectId in the response

# 2. Verify - all shards PASS
curl "http://localhost:8080/api/objects/demo.txt/verify"

# 3. Corrupt shard 0 on node-1 (node stays alive and healthy)
docker exec pemdos-node-1-1 sh -c "echo CORRUPTED > /data/shards/{objectId}/0.shard"

# 4. Verify again - node-1 shows homomorphic FAIL
curl "http://localhost:8080/api/objects/demo.txt/verify"

# 5. Download still works - reconstructed from the remaining 4 valid shards
curl "http://localhost:8080/api/objects/demo.txt"
```

## Demo: fault tolerance boundary

```bash
# Kill 3 nodes - only 2 shards reachable, below the threshold
docker stop pemdos-node-1-1 pemdos-node-2-1 pemdos-node-3-1

# Download fails
curl "http://localhost:8080/api/objects/demo.txt"

# Bring one node back - now exactly 3 shards reachable
docker start pemdos-node-1-1

# Download works again
curl "http://localhost:8080/api/objects/demo.txt"
```

---

## Project structure

```
PEMDOS/
├── common/
│   └── src/main/
│       ├── proto/storage.proto              # gRPC service contract
│       └── java/com/pemdos/common/
│           ├── codec/ReedSolomonCodec.java  # Encode/decode shards
│           └── fingerprint/
│               ├── GaloisField.java         # GF(2^8) arithmetic
│               ├── HomomorphicFingerprint.java  # Paper implementation
│               └── FingerprintService.java  # SHA-256 hashing
├── storage-node/
│   └── src/main/java/com/pemdos/node/
│       ├── grpc/StorageNodeGrpcService.java # gRPC endpoint handlers
│       └── service/ShardStorageService.java # Shard file I/O
├── coordinator/
│   └── src/main/java/com/pemdos/coordinator/
│       ├── config/
│       │   ├── CoordinatorConfig.java       # Bean wiring
│       │   └── NodeInitializer.java         # Node registration + health check
│       ├── controller/
│       │   ├── ObjectStoreController.java   # Upload/download/verify endpoints
│       │   └── NodeController.java          # Live node status endpoint
│       ├── service/
│       │   ├── ObjectStoreService.java      # Core encode/distribute/retrieve logic
│       │   └── NodeClientService.java       # gRPC client calls to storage nodes
│       └── model/
│           ├── StoredObject.java            # Object metadata entity
│           ├── ShardMetadata.java           # Per-shard metadata entity
│           └── StorageNode.java             # Node registry entity
├── demo-clean.sh                            # Recommended end-to-end test script
├── demo.sh                                  # Verbose alternative test script
├── docker-compose.yml                       # postgres + 5 nodes + coordinator
├── PEMDOS-Report.docx                       # Comprehensive project report
└── README.md                                # This file
```

---
