"""
Alice's phone simulation — X25519 ECDH + AES-GCM + Ed25519 signatures.

Flow:
  1. Generate Ed25519 signing key + X25519 encryption key for Alice
  2. Provision Alice & Bob with the bank (registers Ed25519 public key)
  3. Bob is provisioned with a throwaway Ed25519 keypair
  4. Encrypt payment instruction using X25519 ECDH + AES-256-GCM
  5. Sign ciphertext with Alice's Ed25519 key
  6. Upload MeshPacket through mesh or directly to bank
"""
import json
import base64
import time
import uuid
import os
import sys
from typing import Any
import requests
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

BANK_URL   = "http://localhost:8081"
MESH_ENTRY = "http://localhost:5001"
ALICE_VPA  = "alice@bank"
BOB_VPA    = "bob@bank"

USE_MESH = "--mesh" in sys.argv
UPLOAD_URL = f"{MESH_ENTRY}/receive" if USE_MESH else f"{BANK_URL}/api/mesh/upload"

if USE_MESH:
    print("MESH MODE: packet enters at Node-1 -> hops through mesh -> bank\n")
else:
    print("DIRECT MODE: packet sent straight to bank server\n")


# ── 1. Generate Alice's Ed25519 + X25519 keys ─────────────────────────────

alice_ed25519_priv = Ed25519PrivateKey.generate()
alice_ed25519_pub = alice_ed25519_priv.public_key()
alice_ed25519_pub_b64 = base64.b64encode(
    alice_ed25519_pub.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode()

alice_x25519_priv = X25519PrivateKey.generate()


# ── 2. Provision Alice ────────────────────────────────────────────────────

print(f"Provisioning Alice ({ALICE_VPA}) with the bank server...")
provision_resp = requests.post(
    f"{BANK_URL}/api/mesh/provision",
    json={"vpa": ALICE_VPA, "publicKey": alice_ed25519_pub_b64},
    timeout=10)
provision_resp.raise_for_status()
provision_data = provision_resp.json()
print("Provision response:", json.dumps(provision_data, indent=2))

# Server returns its X25519 public key for ECDH key exchange
bank_pub_b64 = provision_data["bankPublicKey"]
bank_pub_key = serialization.load_der_public_key(
    base64.b64decode(bank_pub_b64)
)


# ── 3. Provision Bob ──────────────────────────────────────────────────────

print(f"\nProvisioning Bob ({BOB_VPA}) with the bank server...")
bob_ed25519_priv = Ed25519PrivateKey.generate()
bob_pub_b64 = base64.b64encode(
    bob_ed25519_priv.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
).decode()

bob_resp = requests.post(
    f"{BANK_URL}/api/mesh/provision",
    json={"vpa": BOB_VPA, "publicKey": bob_pub_b64},
    timeout=10)
bob_resp.raise_for_status()
print("Bob provisioned:", bob_resp.json().get("status"))


# ── 4. Build PaymentInstruction ───────────────────────────────────────────

current_millis = int(time.time() * 1000)
payment_instruction = {
    "senderVpa":   ALICE_VPA,
    "receiverVpa": BOB_VPA,
    "amount":      500.00,
    "pinHash":     "DUMMY_PIN_HASH_123",
    "nonce":       str(uuid.uuid4()),
    "signedAt":    current_millis,
}
plain_text_json = json.dumps(payment_instruction)
print(f"\nPayment instruction: Alice -> Bob, Rs.500")


# ── 5. X25519 ECDH + AES-256-GCM encryption ──────────────────────────────

ephemeral_x25519 = X25519PrivateKey.generate()
shared_secret = ephemeral_x25519.exchange(bank_pub_key)

iv = os.urandom(12)

# Derive AES-256 key: SHA-256("upi-aes-key-derivation" || shared_secret || iv)
digest = hashes.Hash(hashes.SHA256())
digest.update(b"upi-aes-key-derivation")
digest.update(shared_secret)
digest.update(iv)
aes_key = digest.finalize()

aesgcm = AESGCM(aes_key)
encrypted_data = aesgcm.encrypt(iv, plain_text_json.encode("utf-8"), None)

enc = base64.urlsafe_b64encode
ciphertext = (
    enc(ephemeral_x25519.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )).decode()
    + ":" + enc(iv).decode()
    + ":" + enc(encrypted_data).decode()
)


# ── 6. Sign ciphertext with Alice's Ed25519 key ───────────────────────────

signature = alice_ed25519_priv.sign(ciphertext.encode("utf-8"))
signature_b64 = base64.b64encode(signature).decode()


# ── 7. Assemble MeshPacket ────────────────────────────────────────────────

mesh_packet: dict[str, Any] = {
    "packetId":  f"PKT_{str(uuid.uuid4())[:8].upper()}",
    "ttl":       10,
    "createdAt": current_millis,
    "senderVpa": ALICE_VPA,
    "signature": signature_b64,
    "ciphertext": ciphertext,
}

print("\nMesh packet assembled. Uploading...")
if USE_MESH:
    print(f"   Route: {MESH_ENTRY}/receive -> Node-1 -> ... -> Bank\n")
else:
    print(f"   Route: {BANK_URL}/api/mesh/upload (direct)\n")


# ── 8. Upload ─────────────────────────────────────────────────────────────

upload_resp = requests.post(
    UPLOAD_URL,
    json=mesh_packet,
    timeout=30)

print(f"\n{'OK' if upload_resp.ok else 'FAIL'} Server response [{upload_resp.status_code}]:")
print(upload_resp.text)

if not upload_resp.ok:
    print("\nPacket rejected. Check server logs for details.")
