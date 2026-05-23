"""
Alice's phone simulation.

Changes from original:
  1. CRITICAL FIX: MGF for RSA-OAEP changed from SHA-1 → SHA-256 to match the Java server.
     Original used: padding.MGF1(algorithm=hashes.SHA1())
     Server expects: RSA/ECB/OAEPWithSHA-256AndMGF1Padding  (MGF1 with SHA-256)
  2. FIX: Bank public key is now fetched dynamically from the /provision response,
     so it stays in sync even after a server restart. The hardcoded BANK_PUBLIC_KEY_B64
     only works until the server is restarted (keys regenerate on every boot).
  3. FIX: receiverVpa changed from "bob@bank" — already correct, but bob must also
     be provisioned via POST /api/mesh/provision before Alice's packet is uploaded.
"""
import json
import base64
import time
import uuid
import os
import requests
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.backends import default_backend

SERVER_URL = "http://localhost:8080"
ALICE_VPA  = "alice@bank"
BOB_VPA    = "bob@bank"
KEY_FILE   = "alice_private.pem"


# ── 1. Load or generate Alice's key pair ────────────────────────────────────

if os.path.exists(KEY_FILE):
    print("🔑 Loaded EXISTING keys for Alice from disk.")
    with open(KEY_FILE, "rb") as f:
        alice_private_key = serialization.load_pem_private_key(
            f.read(), password=None, backend=default_backend())
else:
    print("🔑 Generating NEW keys for Alice and saving to disk...")
    alice_private_key = rsa.generate_private_key(
        public_exponent=65537, key_size=2048, backend=default_backend())
    with open(KEY_FILE, "wb") as f:
        f.write(alice_private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption()))

alice_pub_der = alice_private_key.public_key().public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo)
alice_pub_b64 = base64.b64encode(alice_pub_der).decode()


# ── 2. Provision Alice (registers her public key & creates her account) ──────

print(f"\n📡 Provisioning Alice ({ALICE_VPA}) with the bank server...")
provision_resp = requests.post(
    f"{SERVER_URL}/api/mesh/provision",
    json={"vpa": ALICE_VPA, "publicKey": alice_pub_b64},
    timeout=10)
provision_resp.raise_for_status()
provision_data = provision_resp.json()
print("✅ Provision response:", json.dumps(provision_data, indent=2))

# FIX: use the LIVE bank public key returned from the server, not a hardcoded one.
# The server generates a fresh RSA keypair on every boot, so any static constant
# becomes invalid after a restart.
bank_pub_b64 = provision_data["bankPublicKey"]
bank_pub_key = serialization.load_der_public_key(
    base64.b64decode(bank_pub_b64), backend=default_backend())


# ── 3. Provision Bob (so his account exists as the receiver) ────────────────

print(f"\n📡 Provisioning Bob ({BOB_VPA}) with the bank server...")
bob_key_file = "bob_private.pem"
if os.path.exists(bob_key_file):
    with open(bob_key_file, "rb") as f:
        bob_private_key = serialization.load_pem_private_key(
            f.read(), password=None, backend=default_backend())
else:
    bob_private_key = rsa.generate_private_key(
        public_exponent=65537, key_size=2048, backend=default_backend())
    with open(bob_key_file, "wb") as f:
        f.write(bob_private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption()))

bob_pub_b64 = base64.b64encode(
    bob_private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo)
).decode()

bob_resp = requests.post(
    f"{SERVER_URL}/api/mesh/provision",
    json={"vpa": BOB_VPA, "publicKey": bob_pub_b64},
    timeout=10)
bob_resp.raise_for_status()
print("✅ Bob provisioned:", bob_resp.json().get("status"))


# ── 4. Build the PaymentInstruction ─────────────────────────────────────────

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
print(f"\n📝 Payment instruction: Alice → Bob, ₹500")


# ── 5. Hybrid encryption (AES-256-GCM + RSA-OAEP) ───────────────────────────

aes_key = os.urandom(32)
iv      = os.urandom(12)
aesgcm  = AESGCM(aes_key)
encrypted_data = aesgcm.encrypt(iv, plain_text_json.encode("utf-8"), None)

# MGF uses SHA-256 (not SHA-1) to match Java's RSA/ECB/OAEPWithSHA-256AndMGF1Padding
encrypted_aes_key = bank_pub_key.encrypt(
    aes_key,
    padding.OAEP(
        mgf=padding.MGF1(algorithm=hashes.SHA256()),
        algorithm=hashes.SHA256(),
        label=None
    )
)

# Standard urlsafe_b64encode — Java's getMimeDecoder handles the '=' padding fine.
ciphertext = (
    base64.urlsafe_b64encode(iv).decode()
    + ":" + base64.urlsafe_b64encode(encrypted_aes_key).decode()
    + ":" + base64.urlsafe_b64encode(encrypted_data).decode()
)


# ── 6. Sign the ciphertext with Alice's private key ──────────────────────────

signature = alice_private_key.sign(
    ciphertext.encode("utf-8"),
    padding.PKCS1v15(),
    hashes.SHA256()
)
signature_b64 = base64.b64encode(signature).decode()


# ── 7. Assemble the MeshPacket ───────────────────────────────────────────────

mesh_packet = {
    "packetId":  f"PKT_{str(uuid.uuid4())[:8].upper()}",
    "ttl":        5,
    "createdAt":  current_millis,
    "senderVpa":  ALICE_VPA,
    "signature":  signature_b64,
    "ciphertext": ciphertext,
}

print("\n📦 Mesh packet assembled. Uploading to bank gateway...")


# ── 8. POST to bank gateway ──────────────────────────────────────────────────

upload_resp = requests.post(
    f"{SERVER_URL}/api/mesh/upload",
    json=mesh_packet,
    timeout=10)

print(f"\n{'✅' if upload_resp.ok else '❌'} Server response [{upload_resp.status_code}]:")
print(upload_resp.text)

if not upload_resp.ok:
    print("\n⚠️  Packet rejected. Check server logs for details.")