# Offline UPI Payment — Mesh Network

Send encrypted UPI payments across a mesh of phones with no internet.
Packets hop through 10 nodes using GPSR routing until they reach the bank.

```
Node-1 → Node-5 → Node-9 → Node-10 → Bank → ✅ SETTLED
```

## Quick Start

```bash
docker compose up --build -d
python3 -m venv venv && source venv/bin/activate && pip install cryptography requests
python3 pay.py
```

Open http://localhost:5002 to watch hops animate.

## How it works

- **Mesh routing** — Each phone forwards packets to the neighbor closest to the bank using GPSR
- **Encryption** — X25519 ECDH + AES-256-GCM, signed with Ed25519
- **Security** — 7-step pipeline: replay check, signature verify, decrypt, auth cross-check, freshness, idempotency, atomic transfer
- **Docker** — One command starts the bank (Spring Boot), database (PostgreSQL), 10 mesh nodes (Python), and visualizer (Flask + SSE)

## Project Structure

```
docker-compose.yml     # 13 containers: bank + db + 10 nodes + visualizer
Dockerfile             # Spring Boot multi-stage build
phone-node/            # Python mesh node with GPSR routing
visualizer/            # Flask SSE server + HTML5 Canvas dashboard
src/                   # Spring Boot bank (Java 21)
pay.py                 # One-click payment demo
demo_mesh.py           # Routing visualization demo
mesh-topology.json     # 10-node grid positions and neighbors
```
