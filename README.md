# Offline UPI Payment — Mesh Network

Send encrypted UPI payments across a mesh of phones with **no internet**. Each node dynamically routes packets to the neighbor closest to the bank using **greedy geographic routing**. Watch every hop animate in real-time.

```
         3×3 Grid + Gateway
    ┌──────┬──────┬──────┐
    │Node-1│Node-2│Node-3│
    ├──────┼──────┼──────┤
    │Node-4│Node-5│Node-6│
    ├──────┼──────┼──────┤
    │Node-7│Node-8│Node-9│─── Node-10 ─── 🏦 Bank
    └──────┴──────┴──────┘
```

> **Alice pays Bob ₹500** — packet enters at Node-1, hops greedily through the grid, settles at the bank.

---

## Quick Start

```bash
# 1. Start everything (bank + 10 mesh nodes + live visualizer)
docker compose up --build

# 2. Open the live dashboard
open http://localhost:5002

# 3. Send a test packet — watch it route dynamically!
python3 demo_mesh.py           # fake packet — shows greedy routing
python3 alice_phone.py --mesh  # real ₹500 payment through mesh
```

---

## Greedy Geographic Routing

Unlike a fixed linear chain, every node makes an **independent routing decision**:

```
             Bank (400, 450)
                 ▲
                 │
            Node-10 (400, 300)
                 ▲
            ──── ╱
        Node-9 (300, 300)
           ▲
        ╱
  Node-5 (150, 150)
     ▲
   ╱  ╲
Node-1  Node-2  Node-3
(0,0)  (150,0) (300,0)
```

1. Each node loads `mesh-topology.json` at startup — knows its own position, all neighbors' positions, and the bank's position.
2. When a packet arrives, the node computes Euclidean distance from each neighbor to the bank.
3. It forwards to the **neighbor closest to the bank**.
4. If the node itself is closest, it sends directly to the bank.

**Example path from Node-1:**
| Hop | Node | Neighbors (distance to Bank) | Best choice |
|-----|------|------------------------------|-------------|
| 1 | Node-1 | Node-2 (515), Node-4 (500), Node-5 (391) | **Node-5** |
| 2 | Node-5 | ... Node-9 (180), Node-6 (316), Node-8 (291) | **Node-9** |
| 3 | Node-9 | Node-10 (150), Node-6 (316), Node-8 (291) | **Node-10** |
| 4 | Node-10 | Bank (0), Node-9 (180) | **Bank** |

Path: `Node-1 → Node-5 → Node-9 → Node-10 → Bank` (4 hops vs 10 in a linear chain).

---

## Debug Endpoint

Every node exposes `GET /debug` showing its routing decisions:

```bash
curl http://localhost:5001/debug
```

Response:
```json
{
  "nodeId": "Node-1",
  "position": {"x": 0, "y": 0},
  "selfDistanceToBank": 602.1,
  "neighborDistancesToBank": [
    {"neighbor": "Node-2", "distanceToBank": 514.8},
    {"neighbor": "Node-4", "distanceToBank": 500.0},
    {"neighbor": "Node-5", "distanceToBank": 390.5}
  ],
  "routingHistory": [
    {
      "packetId": "PKT_DEMO_...",
      "chosenGateway": "Node-5",
      "chosenDistToBank": 390.5,
      "neighborOptions": [...]
    }
  ]
}
```

Check any node by its port or internal address:
- Node-1: `http://localhost:5001/debug` (only externally exposed port)
- Other nodes: accessible from within Docker network

---

## Topology

The mesh is defined in `mesh-topology.json` — a 3×3 grid + gateway:

```
Node-1 (0,0)    Node-2 (150,0)   Node-3 (300,0)
Node-4 (0,150)  Node-5 (150,150) Node-6 (300,150)
Node-7 (0,300)  Node-8 (150,300) Node-9 (300,300)
                                    |
                                 Node-10 (400,300)
                                    |
                                 Bank (400,450)
```

Each node connects to orthogonal neighbors **and** diagonal neighbors, creating a rich mesh with many possible paths. The greedy algorithm finds a near-shortest path.

---

## How the Mesh Works

```
Alice's Laptop              Docker Mesh Nodes                  Bank Server
─────────────────────       ─────────────────────             ─────────────
       │                           │                               │
       │  Encrypt AES-256-GCM      │                               │
       │  Wrap key with RSA-OAEP   │                               │
       │  Sign SHA256withRSA       │                               │
       │  Set TTL=10               │                               │
       │                           │                               │
       │─── POST /receive ───────▶ │                               │
       │                           │  greedy nearest to bank       │
       │                           │  TTL 10→9, forward            │
       │                           │      │ (neighbor closest      │
       │                           │      │  to bank)              │
       │                           │  TTL  9→8, forward            │
       │                           │  TTL  8→7, forward            │
       │                           │  TTL  7→6, forward            │
       │                           │  TTL  6→5, forward ─────────▶ │
       │                           │                               │
       │                           │    1. Replay check            │
       │                           │    2. Signature verify        │
       │                           │    3. RSA unwrap key          │
       │                           │    4. AES-GCM decrypt         │
       │                           │    5. Cross-check VPA         │
       │                           │    6. Freshness 24h           │
       │                           │    7. Balance check           │
       │                           │    8. Transfer ₹500           │
       │                           │    9. Audit record            │
       │                           │    ─── ✅ SETTLED ──          │
       │◀─── nested response ────  │◀──────────────────────────────│
```

Every node (and the bank) reports each step to the visualizer in real-time:

```
  Node-1  ──┐
  Node-2  ──┤
  Node-3  ──┤
  Node-4  ──┤
  Node-5  ──┼──▶ POST /hop ──▶ Visualizer ──▶ SSE ──▶ Browser Canvas
  Node-6  ──┤                    (Flask)          (live animation)
  Node-7  ──┤
  Node-8  ──┤
  Node-9  ──┤
  Node-10 ──┤
  Bank    ──┘
```

---

## Demo Scenarios

| Command | What happens |
|---------|-------------|
| `python3 demo_mesh.py` | 📱 Node-1 → Node-5 → Node-9 → Node-10 → 🏦 Bank (fake crypto, shows greedy routing visually) |
| `python3 alice_phone.py` | 🏦 Bank RECEIVED → SETTLED (direct upload, bypasses mesh) |
| `python3 alice_phone.py --mesh` | 📱 Node-1 → Node-5 → Node-9 → Node-10 → 🏦 Bank RECEIVED → **SETTLED ✓** (₹500 Alice → Bob, greedy path) |

---

## Security Pipeline (8 Checks)

```
 1. Replay?     → SHA-256(ciphertext) already in DB?    → REJECT
 2. Known user? → senderVpa registered?                  → REJECT
 3. Tampered?   → RSA signature valid?                   → REJECT
 4. Decrypt?    → RSA unwrap + AES-GCM decrypt?          → REJECT
 5. Spoofed?    → outer VPA == inner VPA?                → REJECT
 6. Too old?    → signedAt within 24 hours?               → REJECT
 7. Has funds?  → sufficient balance?                     → REJECT
 8. Audit       → persist Transaction →                  ✅ SETTLED
```

- **Hybrid encryption**: AES-256-GCM payload, RSA-2048 OAEP key wrap (MGF1 SHA-256)
- **Digital signature**: SHA256withRSA, PKCS#1 v1.5 padding
- **TTL**: hop-limit prevents infinite loops
- **Replay protection**: duplicate packet hash rejected instantly
- **Fresh keypair**: bank generates new RSA keypair on every boot

---

## Project Structure

```
payment/
├── mesh-topology.json           # 10-node grid positions, neighbors, URLs
├── docker-compose.yml           # 12 containers: visualizer + bank + 10 nodes
├── Dockerfile                   # Spring Boot bank build
│
├── src/main/java/.../           # Bank server (Java 21 + Spring Boot 3.2)
│   ├── PaymentController.java        # POST /api/mesh/upload
│   ├── TestProvisionController.java  # POST /api/mesh/provision
│   ├── PaymentProcessorService.java  # 8-step security pipeline
│   ├── LedgerService.java            # Fund transfer logic
│   ├── HybridCryptoService.java      # AES-256-GCM + RSA-OAEP
│   ├── SignatureService.java         # SHA256withRSA verification
│   ├── model/                        # MeshPacket, Transaction, User, Account, etc.
│   └── config/DataInitializer.java   # Demo data seeding
│
├── phone-node/                  # Mesh node (Python 3.11 + Flask)
│   ├── Dockerfile
│   ├── requirements.txt
│   └── node.py                      # POST /receive → greedy routing → forward
│                                    # GET  /debug  → routing table & history
│                                    # GET  /health → liveness check
│
├── visualizer/                  # Live dashboard (Python 3.11 + Flask)
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── visualizer.py                # SSE server + hop recording
│   └── templates/dashboard.html     # HTML5 Canvas animation
│
├── alice_phone.py               # Simulates Alice's phone (keygen, encrypt, sign, send)
├── bob_phone.py                 # Generates Bob's keys for provisioning
├── demo_mesh.py                 # Quick demo — dummy packet through greedy mesh
└── test-mesh.http               # Manual HTTP test sequence (REST Client)
```

---

## API Reference

### Bank (`localhost:8080`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/mesh/provision` | Register VPA + RSA public key. Returns bank's public key. |
| POST | `/api/mesh/upload` | Submit encrypted payment packet. Returns `200 ✅ SETTLED` or error. |

### Visualizer (`localhost:5002`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | HTML dashboard |
| POST | `/hop` | Nodes/bank report events here |
| GET | `/events` | SSE feed (pushes events to browser) |
| POST | `/reset` | Clear all events |

### Mesh Nodes

| Method | Path | Description |
|--------|------|-------------|
| POST | `/receive` | Entry point for mesh packets |
| GET | `/debug` | Routing table, neighbor distances, decision history |
| GET | `/health` | Liveness check |

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Bank Server | Java 21, Spring Boot 3.2.5, H2, Maven |
| Mesh Nodes | Python 3.11, Flask |
| Visualizer | Python 3.11, Flask, HTML5 Canvas, SSE |
| Orchestration | Docker Compose |
| Crypto | RSA-2048, AES-256-GCM, SHA-256, SHA256withRSA |

---

## Adding or Removing Nodes

The greedy routing is fully topology-agnostic. To modify the mesh:

1. Update `mesh-topology.json` — add/remove nodes, positions, neighbors, URLs.
2. Update `docker-compose.yml` — add/remove service definitions (each needs the volume mount).
3. Rebuild: `docker compose up --build`

No code changes needed — `node.py` derives all routing from the config file.
