import json, sys, os, time, uuid, base64, requests
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BANK_URL = "http://localhost:8081"
MESH_ENTRY = "http://localhost:5001"

alice_ed = Ed25519PrivateKey.generate()
alice_pub_b64 = base64.b64encode(
    alice_ed.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode()

# Provision Alice
r = requests.post(f"{BANK_URL}/api/mesh/provision",
    json={"vpa": "alice@bank", "publicKey": alice_pub_b64}, timeout=10)
prov = r.json()
bank_pub_key = serialization.load_der_public_key(
    base64.b64decode(prov["bankPublicKey"]))
print(f"✓ Alice provisioned ({prov['vpa']})")
print(f"  Bank public key: {prov['bankPublicKey'][:20]}...")
print(f"  Bank signing key: {prov['bankSigningKey'][:20]}...")

# Provision Bob
bob_ed = Ed25519PrivateKey.generate()
bob_pub_b64 = base64.b64encode(
    bob_ed.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode()
r2 = requests.post(f"{BANK_URL}/api/mesh/provision",
    json={"vpa": "bob@bank", "publicKey": bob_pub_b64}, timeout=10)
print(f"✓ Bob provisioned ({r2.json()['vpa']})")

# Encrypt
now = int(time.time() * 1000)
plain = json.dumps({"senderVpa":"alice@bank","receiverVpa":"bob@bank",
    "amount":500.00,"pinHash":"x","nonce":str(uuid.uuid4()),"signedAt":now})
eph = X25519PrivateKey.generate()
secret = eph.exchange(bank_pub_key)
iv = os.urandom(12)
h = hashes.Hash(hashes.SHA256())
h.update(b"upi-aes-key-derivation"); h.update(secret); h.update(iv)
aes_key = h.finalize()
enc = base64.urlsafe_b64encode
ct = enc(eph.public_key().public_bytes(encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo)).decode() + \
    ":" + enc(iv).decode() + ":" + enc(AESGCM(aes_key).encrypt(iv, plain.encode(), None)).decode()
sig = base64.b64encode(alice_ed.sign(ct.encode())).decode()

packet = {"packetId": f"PKT_{uuid.uuid4().hex[:8].upper()}", "ttl": 10,
    "createdAt": now, "senderVpa": "alice@bank", "signature": sig, "ciphertext": ct}

resp = requests.post(f"{MESH_ENTRY}/receive", json=packet, timeout=30)

# Flatten nested response into a path
path = []
def flatten(d):
    if isinstance(d, dict):
        s = d.get("status", "")
        if "Forwarded" in s:
            path.append(s.replace("Forwarded by ", "").replace(" to ", " --> "))
        elif "success" in d.get("status", ""):
            path.append("✅ SETTLED — Transaction Successful")
        elif "error" in d:
            path.append(f"❌ REJECTED — {d.get('message', '')}")
        if "downstream_response" in d:
            flatten(d["downstream_response"])
flatten(resp.json())

print("")
print("  ■ UPI PAYMENT — MESH NETWORK")
print("  ───────────────────────────")
print(f"  Alice --> ₹500 --> Bob")
print("")
print("  📱 alice_phone.py")
print("     │")
print("     ├── Encrypt (X25519 + AES-256-GCM)")
print("     ├── Sign (Ed25519)")
print("     │")
print("     ▼ POST /receive")
print("")
print("  🌐 Mesh Route:")
for p in path:
    print(f"     {p}")
print("")
