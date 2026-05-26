"""
1M-user load test — parallel provision + concurrency sweep.
Usage: LOAD_USERS=100000 python3 load_test_1m.py
"""
import json, time, uuid, base64, os, sys, statistics, threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import random, hashlib

BANK_URLS = os.getenv("BANK_URLS", "http://localhost:8081,http://localhost:8082,http://localhost:8083").split(",")
TOTAL_USERS = int(os.getenv("LOAD_USERS", "500"))
PAR_PROVISION = int(os.getenv("PAR_PROVISION", "50"))
PAR_PAYMENT = int(os.getenv("PAR_PAYMENT", "200"))

stats_lock = threading.Lock()
stats = {"ok": 0, "fail": 0, "latencies": [], "status_codes": {}}
start_time = 0

from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend

def pick_bank(vpa=None):
    if vpa:
        return BANK_URLS[hash(vpa) % len(BANK_URLS)]
    return random.choice(BANK_URLS)

def http_post(url_path, body_dict, timeout=60, vpa=None):
    bank = pick_bank(vpa)
    data = json.dumps(body_dict, separators=(",", ":")).encode()
    req = Request(f"{bank}{url_path}", data=data, method="POST")
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
    sk = ed25519.Ed25519PrivateKey.generate()
    pk = base64.b64encode(sk.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    return sk, pk

def provision_pair(i):
    sk, sp = gen_ed25519()
    sv = f"ls_{i}@bank"
    code, body = http_post("/api/mesh/provision", {"vpa": sv, "publicKey": sp}, vpa=sv)
    if code != 200:
        return None
    bpk = json.loads(body).get("bankPublicKey")
    rk, rp = gen_ed25519()
    rv = f"lr_{i}@bank"
    code2, _ = http_post("/api/mesh/provision", {"vpa": rv, "publicKey": rp}, vpa=rv)
    if code2 != 200:
        return None
    return sv, rv, sk, bpk

def make_packet(sv, rv, ed_priv_key, bank_enc_b64, amount=0.01):
    ts = int(time.time() * 1000)
    instr = json.dumps({
        "senderVpa": sv, "receiverVpa": rv, "amount": amount,
        "pinHash": hashlib.sha256(b"p").hexdigest(),
        "nonce": str(uuid.uuid4()), "signedAt": ts
    }, separators=(",", ":")).encode()
    bp = serialization.load_der_public_key(base64.b64decode(bank_enc_b64), backend=default_backend())
    eph_priv = x25519.X25519PrivateKey.generate()
    eph_pub = eph_priv.public_key()
    shared = eph_priv.exchange(bp)
    iv = os.urandom(12)
    h = hashlib.sha256()
    h.update(b"upi-aes-key-derivation"); h.update(shared); h.update(iv)
    aes_key = h.digest()
    c = Cipher(algorithms.AES(aes_key), modes.GCM(iv), backend=default_backend())
    e = c.encryptor()
    ct = e.update(instr) + e.finalize() + e.tag
    ct_b64 = (base64.urlsafe_b64encode(eph_pub.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).rstrip(b"=").decode() + ":" +
              base64.urlsafe_b64encode(iv).rstrip(b"=").decode() + ":" +
              base64.urlsafe_b64encode(ct).rstrip(b"=").decode())
    sig = base64.b64encode(ed_priv_key.sign(ct_b64.encode())).decode()
    return {"packetId": str(uuid.uuid4()), "ttl": 5, "createdAt": ts,
            "senderVpa": sv, "signature": sig, "ciphertext": ct_b64}

def send_one(pkt):
    t0 = time.time()
    code, body = http_post("/api/mesh/upload", pkt, 60, vpa=pkt["senderVpa"])
    lat = time.time() - t0
    with stats_lock:
        stats["latencies"].append(lat)
        stats["status_codes"][code] = stats["status_codes"].get(code, 0) + 1
        if code == 200: stats["ok"] += 1
        else: stats["fail"] += 1

def main():
    global stats, start_time
    n = TOTAL_USERS
    print("=" * 70)
    print(f"  1M-USER LOAD TEST — Ed25519 + X25519  ({n} pairs)")
    print("=" * 70)

    print(f"\n  Phase 1: Provisioning {n*2} users (parallel={PAR_PROVISION})...")
    t0 = time.time()
    users = []
    done = 0
    prov_lock = threading.Lock()
    def prov(i):
        nonlocal done
        r = provision_pair(i)
        with prov_lock:
            done += 1
            if r:
                users.append(r)
            if done % max(1, n//20) == 0:
                elapsed = time.time() - t0
                rate = done / elapsed if elapsed > 0 else 0
                eta = (n - done) / rate if rate > 0 else 0
                print(f"  → {done}/{n} pairs  ({rate:.0f}/s, ETA {eta:.0f}s)")
        return r
    with ThreadPoolExecutor(max_workers=PAR_PROVISION) as ex:
        list(ex.map(prov, range(n)))
    print(f"  → {len(users)} pairs provisioned in {time.time()-t0:.0f}s")

    levels = [c for c in [25, 50, 100, 200, 400] if c <= len(users)]
    if not levels:
        print("\n  ⚠ Not enough users for any concurrency level. Increase LOAD_USERS.")
        return
    results = []
    for conc in levels:
        if conc > len(users): continue
        stats = {"ok": 0, "fail": 0, "latencies": [], "status_codes": {}}
        idx = levels.index(conc) + 1
        print(f"\n  Phase 2.{idx}: Generating {len(users)} fresh packets...")
        t_gen = time.time()
        with ThreadPoolExecutor(max_workers=PAR_PAYMENT) as ex:
            pkts = list(ex.map(lambda u: make_packet(*u), users))
        print(f"  → {len(pkts)} packets in {time.time()-t_gen:.1f}s")

        start_time = time.time()
        print(f"  Sending {len(pkts)} payments at concurrency={conc}...", end=" ", flush=True)
        stop = threading.Event()
        def reporter():
            while not stop.is_set():
                stop.wait(0.5)
                e = time.time() - start_time
                with stats_lock:
                    t = stats["ok"] + stats["fail"]
                if e > 0 and t > 0:
                    print(f"\r  → {t}/{len(pkts)} | {t/e:.0f} req/s", end="", flush=True)
        rt = threading.Thread(target=reporter, daemon=True)
        rt.start()
        bs = max(1, len(pkts) // conc)
        batches = [pkts[i:i+bs] for i in range(0, len(pkts), bs)]
        with ThreadPoolExecutor(max_workers=conc) as ex:
            list(ex.map(lambda b: [send_one(p) for p in b], batches))
        stop.set()
        rt.join()
        elapsed = time.time() - start_time
        with stats_lock:
            o = stats["ok"]; f_ = stats["fail"]; lats = sorted(stats["latencies"])
        tps = (o + f_) / elapsed if elapsed > 0 else 0
        p50 = lats[len(lats)//2]*1000 if lats else 0
        p95 = lats[int(len(lats)*0.95)]*1000 if lats else 0
        p99 = lats[int(len(lats)*0.99)]*1000 if lats else 0
        print(f"\r  → {o+f_}/{len(pkts)} | {tps:.0f} req/s | OK={o} Fail={f_} | P50={p50:.0f} P95={p95:.0f} P99={p99:.0f}ms")
        results.append((conc, tps, o, f_, statistics.mean(lats)*1000 if lats else 0, p50, p95, p99))

    print(f"\n{'='*70}")
    print(f"  LOAD TEST SUMMARY")
    print(f"{'='*70}")
    print(f"  {'Conc':>6} | {'OK':>6} | {'Fail':>5} | {'TPS':>8} | {'Avg(ms)':>8} | {'P50':>8} | {'P95':>8} | {'P99':>8}")
    print(f"  {'-'*6}-+-{'-'*6}-+-{'-'*5}-+-{'-'*8}-+-{'-'*8}-+-{'-'*8}-+-{'-'*8}-+-{'-'*8}")
    for r in results:
        print(f"  {r[0]:>6} | {r[2]:>6} | {r[3]:>5} | {r[1]:>7.0f} | {r[4]:>7.0f} | {r[5]:>7.0f} | {r[6]:>7.0f} | {r[7]:>7.0f}")

    best_tps = max(r[1] for r in results)
    max_users = best_tps * 86400 / 3
    print(f"\n  Peak throughput:  {best_tps:.0f} req/s")
    print(f"  Required for 1M:  {3*1_000_000/86400:.0f} req/s (~3 tx/user/day)")
    print(f"  {'✅' if best_tps >= 3*1_000_000/86400 else '❌'} {'CAN' if best_tps >= 3*1_000_000/86400 else 'CANNOT'} handle 1M users")
    print(f"  Theoretical max:  ~{max_users:,.0f} users")

if __name__ == "__main__":
    main()
