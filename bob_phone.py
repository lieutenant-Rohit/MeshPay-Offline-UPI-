import json
import base64
import os
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend

# The file where Bob's phone stores his key
KEY_FILE = "bob_private.pem"

def main():
    print("📱 Starting Bob's Phone Simulation...\n")

    # Save and Load Bob's Keys from a file (Prevents desync)
    if os.path.exists(KEY_FILE):
        print("🔑 Loaded EXISTING keys for Bob from disk.")
        with open(KEY_FILE, "rb") as key_file:
            bob_private_key = serialization.load_pem_private_key(
                key_file.read(),
                password=None,
                backend=default_backend()
            )
    else:
        print("🔑 Generating NEW keys for Bob and saving to disk...")
        bob_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048, backend=default_backend())
        with open(KEY_FILE, "wb") as key_file:
            key_file.write(bob_private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption()
            ))

    bob_public_key_der = bob_private_key.public_key().public_bytes(encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo)
    bob_public_key_b64 = base64.b64encode(bob_public_key_der).decode('utf-8')

    print("\n✅ BOB'S DATA (Paste this into demo.http to register Bob)")
    provision_json = {
        "vpa": "bob@bank",
        "publicKey": bob_public_key_b64
    }
    print(json.dumps(provision_json, indent=2))

if __name__ == "__main__":
    main()