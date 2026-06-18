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

print("╔══════════════════════════════════════════════════════╗")
print("║     UPI MESH PACKET VISUALIZER — DEMO               ║")
print("╠══════════════════════════════════════════════════════╣")
print(f"║  Packet ID : {PACKET['packetId']}")
print(f"║  TTL       : {PACKET['ttl']}")
print(f"║  Entry     : Node-1 (port 5001)")
print("║                                                    ║")
print("║  Open your browser to:                              ║")
print("║  ► http://localhost:5002                             ║")
print("╚══════════════════════════════════════════════════════╝")

resp = requests.post(MESH_ENTRY, json=PACKET, timeout=30)
result = resp.json()

print(f"\n{'✅' if resp.ok else '❌'} Mesh response [{resp.status_code}]:")
print(json.dumps(result, indent=2))

if resp.ok:
    print("\n🎯 Packet traversed the mesh! Check the visualizer.")
else:
    print("\n⚠️  Packet was rejected (expected if using demo signature).")
    print("   The visualizer still shows the mesh hops!")
