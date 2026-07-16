"""
Quick demo: sends a packet through the mesh so you can watch it hop
node-to-node in the visualizer at http://localhost:5002

Usage:
    python demo_mesh.py

Prerequisites:
    docker compose up   (bank + 5 nodes + visualizer running)
"""
import json, uuid, time, requests

MESH_ENTRY = "http://localhost:5001/receive"

PACKET = {
    "packetId": f"PKT_DEMO_{str(uuid.uuid4())[:8].upper()}",
    "ttl": 10,
    "mode": "greedy",
    "createdAt": int(time.time() * 1000),
    "senderVpa": "alice@bank",
    "signature": "DEMO_SIGNATURE_FOR_VISUALIZATION",
    "ciphertext": "DEMO_CIPHERTEXT_FOR_VISUALIZATION",
}

print("  ╔══════════════════════════════════════════════════════╗")
print("  ║     UPI MESH PACKET VISUALIZER — DEMO                ║")
print("  ╠══════════════════════════════════════════════════════╣")
print(f" ║  Packet ID : {PACKET['packetId']:<47}                ║")
print(f" ║  TTL       : {PACKET['ttl']:<47}                     ║")
print(f" ║  Entry     : Node-1 (port 5001)                      ║")
print("  ║                                                      ║")
print("  ║  Open your browser to:                               ║")
print("  ║  ► http://localhost:5002                             ║")
print("  ╚══════════════════════════════════════════════════════╝")

resp = requests.post(MESH_ENTRY, json=PACKET, timeout=30)
result = resp.json()

# Flatten nested response into a clean route path
path = []
def flatten(d):
    if isinstance(d, dict):
        s = d.get("status", "")
        if "Forwarded" in s:
            path.append(s.replace("Forwarded by ", "").replace(" to ", " --> "))
        elif "success" in d.get("status", ""):
            path.append("✅ SETTLED")
        elif "error" in d:
            path.append("⚠️  Rejected (demo signature)")
        if "downstream_response" in d:
            flatten(d["downstream_response"])
flatten(result)

print("")
print("  🌐 Mesh Route:")
for p in path:
    print(f"     {p}")
print(f"  📺 http://localhost:5002")
