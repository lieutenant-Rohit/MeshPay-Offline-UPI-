"""
Final load test — Provisions N users once, then runs at multiple concurrency levels.
Uses Ed25519 signatures + X25519 ECDH + AES-GCM (fast crypto path).
"""
import json, time, uuid, base64, os, sys, statistics, threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

import random
BANK_URLS = os.getenv("BANK_URLS", "http://localhost:8081,http://localhost:8082,http://localhost:8083").split(",")
TOTAL_USERS = int(os.getenv("LOAD_USERS", "500"))

lock = threading.Lock()
stats = {"ok": 0, "fail": 0, "latencies": [], "status_codes": {}}
start_time = 0

req_index = 0
req_lock = threading.Lock()

def http_post(url_path, body_dict, timeout=60, vpa=None):
    global req_index
    if vpa:
        idx = hash(vpa) % len(BANK_URLS)
        bank_url = BANK_URLS[idx]
    else:
        with req_lock:
            bank_url = BANK_URLS[req_index % len(BANK_URLS)]
            req_index += 1
    data = json.dumps(body_dict).encode()
    req = Request(f"{bank_url}{url_path}", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    if vpa:
        req.add_header("X-Sender-Vpa", vpa)
    try:
        resp = urlopen(req, timeout=timeout)
        return resp.status, resp.read().decode()
    except HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return 0, str(e)

def gen_ed25519():
    from cryptography.hazmat.primitives.asymmetric import ed25519
    sk = ed25519.Ed25519PrivateKey.generate()
    pk = sk.public_key()
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    return sk, base64.b64encode(pk.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()

def gen_x25519():
    from cryptography.hazmat.primitives.asymmetric import x25519
    sk = x25519.X25519PrivateKey.generate()
    pk = sk.public_key()
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    return sk, base64.b64encode(pk.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)).decode()

def provision_user(vpa, pub_b64):
    code, body = http_post("/api/mesh/provision", {"vpa": vpa, "publicKey": pub_b64}, vpa=vpa)
    if code == 200:
        return json.loads(body).get("bankPublicKey")
    return None

def make_packet(sv, rv, ed_priv_key, bank_enc_b64, amount=0.01):
    import hashlib
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import x25519
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.backends import default_backend

    ts = int(time.time() * 1000)
    instr = json.dumps({
        "senderVpa": sv, "receiverVpa": rv, "amount": amount,
        "pinHash": hashlib.sha256(b"p").hexdigest(),
        "nonce": str(uuid.uuid4()), "signedAt": ts
    }, separators=(",", ":")).encode()

    # X25519 ECDH + AES-GCM encryption
    bp = serialization.load_der_public_key(base64.b64decode(bank_enc_b64), backend=default_backend())
    eph_priv = x25519.X25519PrivateKey.generate()
    eph_pub = eph_priv.public_key()

    shared = eph_priv.exchange(bp)
    iv = os.urandom(12)

    h = hashlib.sha256()
    h.update(b"upi-aes-key-derivation")
    h.update(shared)
    h.update(iv)
    aes_key = h.digest()

    c = Cipher(algorithms.AES(aes_key), modes.GCM(iv), backend=default_backend())
    e = c.encryptor()
    ct = e.update(instr) + e.finalize() + e.tag

    ct_b64 = (base64.urlsafe_b64encode(eph_pub.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )).rstrip(b"=").decode() + ":" +
              base64.urlsafe_b64encode(iv).rstrip(b"=").decode() + ":" +
              base64.urlsafe_b64encode(ct).rstrip(b"=").decode())

    # Ed25519 signature (signs raw data; Ed25519 internally hashes)
    sig = base64.b64encode(ed_priv_key.sign(ct_b64.encode())).decode()

    return {"packetId": str(uuid.uuid4()), "ttl": 5, "createdAt": ts,
            "senderVpa": sv, "signature": sig, "ciphertext": ct_b64}

def send_one(pkt):
    t0 = time.time()
    code, body = http_post("/api/mesh/upload", pkt, 60, vpa=pkt["senderVpa"])
    lat = time.time() - t0
    with lock:
        stats["latencies"].append(lat)
        stats["status_codes"][code] = stats["status_codes"].get(code, 0) + 1
        if code == 200:
            stats["ok"] += 1
        else:
            stats["fail"] += 1

def run_test(packets, concurrency, label):
    global stats
    stats = {"ok": 0, "fail": 0, "latencies": [], "status_codes": {}}
    global start_time
    start_time = time.time()

    print(f"\n  [{label}] Sending {len(packets)} payments at concurrency={concurrency}...", end=" ", flush=True)

    stop = threading.Event()
    def reporter():
        while not stop.is_set():
            stop.wait(0.3)
            e = time.time() - start_time
            with lock:
                t = stats["ok"] + stats["fail"]
            if e > 0 and t > 0:
                print(f"\r  [{label}] {t}/{len(packets)} | {t/e:.0f} req/s", end="", flush=True)

    rt = threading.Thread(target=reporter, daemon=True)
    rt.start()

    bs = max(1, len(packets) // concurrency)
    batches = [packets[i:i+bs] for i in range(0, len(packets), bs)]

    def worker(batch):
        for p in batch:
            send_one(p)

    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        list(ex.map(worker, batches))

    stop.set()
    rt.join()

    elapsed = time.time() - start_time
    with lock:
        o = stats["ok"]
        f_ = stats["fail"]
        lats = sorted(stats["latencies"])
    tps = (o + f_) / elapsed if elapsed > 0 else 0
    p50 = lats[len(lats)//2]*1000 if lats else 0
    p95 = lats[int(len(lats)*0.95)]*1000 if lats else 0
    p99 = lats[int(len(lats)*0.99)]*1000 if lats else 0
    avg = statistics.mean(lats)*1000 if lats else 0

    print(f"\r  [{label}] {o+f_}/{len(packets)} | {tps:.0f} req/s | "
          f"OK={o} | Fail={f_} | P50={p50:.0f}ms P95={p95:.0f}ms P99={p99:.0f}ms")
    return tps, o, f_, avg, p50, p95, p99

def main():
    print("=" * 70)
    print("  UPI BACKEND LOAD TEST — Ed25519 + X25519 fast crypto")
    print("=" * 70)

    n = TOTAL_USERS

    print(f"\n  Phase 1: Provisioning {n*2} users ({n} pairs)...")
    t0 = time.time()
    users = []
    for i in range(n):
        sk, sp = gen_ed25519()
        sv = f"ls_{i}@bank"
        bpk = provision_user(sv, sp)
        if not bpk:
            continue
        # Receiver uses a separate Ed25519 keypair (for receiving signed receipts)
        rk, rp = gen_ed25519()
        rv = f"lr_{i}@bank"
        rbpk = provision_user(rv, rp)
        if not rbpk:
            print(f"\n  ⚠ Receiver provision failed for {rv}")
            continue
        users.append((sv, rv, sk, bpk))
        if (i+1) % max(1,n//20) == 0:
            print(f"  → {i+1}/{n} pairs ({time.time()-t0:.0f}s)")
    print(f"  → {len(users)} pairs done in {time.time()-t0:.0f}s")

    levels = [25, 50, 100, 200, 400]
    results = []
    for conc in levels:
        if conc > len(users):
            continue
        print(f"\n  Phase 2.{levels.index(conc)+1}: Generating {n} fresh packets...")
        pkts = [make_packet(*u) for u in users]

        tps, ok, fail, avg, p50, p95, p99 = run_test(pkts, min(conc, len(pkts)), f"Conc={conc}")
        results.append((conc, tps, ok, fail, avg, p50, p95, p99))

    print(f"\n{'='*70}")
    print(f"  LOAD TEST SUMMARY (Ed25519 + X25519)")
    print(f"{'='*70}")
    print(f"  {'Conc':>6} | {'OK':>6} | {'Fail':>5} | {'TPS':>8} | {'Avg(ms)':>8} | {'P50':>8} | {'P95':>8} | {'P99':>8}")
    print(f"  {'-'*6}-+-{'-'*6}-+-{'-'*5}-+-{'-'*8}-+-{'-'*8}-+-{'-'*8}-+-{'-'*8}-+-{'-'*8}")
    for r in results:
        print(f"  {r[0]:>6} | {r[2]:>6} | {r[3]:>5} | {r[1]:>7.0f} | {r[4]:>7.0f} | {r[5]:>7.0f} | {r[6]:>7.0f} | {r[7]:>7.0f}")

    best_tps = max(r[1] for r in results)
    best_conc = max(r[0] for r in results if r[1] == best_tps)
    req_for_1b = 3 * 1_000_000_000 / 86400
    max_users = best_tps * 86400 / 3

    print(f"\n  Peak throughput:  {best_tps:.0f} req/s at concurrency={best_conc}")
    print(f"  Required for 1B:  {req_for_1b:.0f} req/s (~3 tx/user/day)")
    print(f"  {'✅' if best_tps >= req_for_1b else '❌'} Backend {'CAN' if best_tps >= req_for_1b else 'CANNOT'} handle 1B users")
    print(f"  Theoretical max:  ~{max_users:,.0f} users")

if __name__ == "__main__":
    main()
