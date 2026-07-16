import os, json, math, time, requests, socket, struct, threading
from flask import Flask, request, jsonify

CONFIG_PATH = "/app/mesh-topology.json"
app = Flask(__name__)

NODE_ID = os.environ.get("NODE_ID", "Unknown-Phone")
VISUALIZER_URL = os.environ.get("VISUALIZER_URL")
BLE_DISCOVERY_RANGE = float(os.environ.get("BLE_DISCOVERY_RANGE", "250"))

with open(CONFIG_PATH) as f:
    config = json.load(f)

nodes_map = {n["id"]: n for n in config["nodes"]}
node_urls = config["nodeUrls"]
BANK_URL = config["bankUrl"]
BANK_NODE_ID = config["bankNodeId"]
bank_pos = nodes_map[BANK_NODE_ID]
my_pos = nodes_map[NODE_ID]

# =================== Feature 1: BLE Discovery ===================
discovered_peers = {}
discovery_lock = threading.Lock()

MCAST_GRP = "224.1.1.1"
MCAST_PORT = 5007
BLE_INTERVAL = 2
BLE_TIMEOUT = 10

def ble_broadcaster():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    while True:
        msg = json.dumps({"nodeId": NODE_ID, "x": my_pos["x"], "y": my_pos["y"]})
        try:
            sock.sendto(msg.encode(), (MCAST_GRP, MCAST_PORT))
        except:
            pass
        time.sleep(BLE_INTERVAL)

def ble_listener():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(('', MCAST_PORT))
    mreq = struct.pack("4sl", socket.inet_aton(MCAST_GRP), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    sock.settimeout(1.0)
    while True:
        try:
            data, _ = sock.recvfrom(1024)
            peer = json.loads(data.decode())
            pid = peer.get("nodeId")
            if not pid or pid == NODE_ID:
                continue
            d = math.sqrt((peer["x"] - my_pos["x"]) ** 2 + (peer["y"] - my_pos["y"]) ** 2)
            if d > BLE_DISCOVERY_RANGE:
                continue
            url = node_urls.get(pid)
            with discovery_lock:
                discovered_peers[pid] = {
                    "id": pid, "x": peer["x"], "y": peer["y"],
                    "last_seen": time.time(), "url": url, "online": True
                }
        except socket.timeout:
            continue
        except:
            continue

def ble_stale_checker():
    while True:
        time.sleep(1)
        now = time.time()
        with discovery_lock:
            for info in discovered_peers.values():
                if info["online"] and (now - info["last_seen"]) > BLE_TIMEOUT:
                    info["online"] = False

threading.Thread(target=ble_broadcaster, daemon=True).start()
threading.Thread(target=ble_listener, daemon=True).start()
threading.Thread(target=ble_stale_checker, daemon=True).start()

def get_online_peers():
    with discovery_lock:
        return {pid: info for pid, info in discovered_peers.items() if info["online"]}

def get_discovered_peer_ids():
    with discovery_lock:
        return [pid for pid, info in discovered_peers.items() if info["online"]]

# =================== Feature 2: Offline Queue ===================
offline_queue = []
offline_queue_lock = threading.Lock()

def queue_packet(packet, target_url, target_node):
    with offline_queue_lock:
        offline_queue.append({
            "packet": packet,
            "target_url": target_url,
            "target_node": target_node,
            "attempts": 0,
            "next_retry": time.time() + 2,
            "enqueued_at": time.time()
        })

def offline_retry_worker():
    while True:
        time.sleep(1)
        now = time.time()
        to_retry = []
        with offline_queue_lock:
            for entry in offline_queue[:]:
                if entry["next_retry"] <= now:
                    to_retry.append(entry)
        for entry in to_retry:
            try:
                resp = requests.post(
                    entry["target_url"], json=entry["packet"], timeout=15
                )
                print(f"[{NODE_ID}] Offline retry #{entry['attempts'] + 1} successful -> {entry['target_node']}")
                report_to_visualizer({
                    "node_id": NODE_ID, "packet_id": entry["packet"].get("packetId", "UNKNOWN"),
                    "action": "FORWARDED", "ttl": entry["packet"].get("ttl", 0),
                    "to_node": entry["target_node"],
                    "message": f"{NODE_ID} -> {entry['target_node']} (offline retry #{entry['attempts'] + 1})",
                    "timestamp": int(time.time() * 1000)
                })
                with offline_queue_lock:
                    if entry in offline_queue:
                        offline_queue.remove(entry)
            except Exception as e:
                entry["attempts"] += 1
                if entry["attempts"] >= 5:
                    print(f"[{NODE_ID}] Dropping queued packet after {entry['attempts']} retries to {entry['target_node']}: {e}")
                    report_to_visualizer({
                        "node_id": NODE_ID, "packet_id": entry["packet"].get("packetId", "UNKNOWN"),
                        "action": "DROPPED", "ttl": entry["packet"].get("ttl", 0),
                        "message": f"{NODE_ID} dropped offline packet to {entry['target_node']} after {entry['attempts']} retries",
                        "timestamp": int(time.time() * 1000)
                    })
                    with offline_queue_lock:
                        if entry in offline_queue:
                            offline_queue.remove(entry)
                else:
                    wait = 2 ** entry["attempts"]
                    entry["next_retry"] = time.time() + wait
                    print(f"[{NODE_ID}] Offline retry #{entry['attempts']} failed to {entry['target_node']}, next in {wait}s")

threading.Thread(target=offline_retry_worker, daemon=True).start()

# =================== Routing ===================
current_routing_mode = "GREEDY"
last_routing_decision = {}
routing_history = []

def distance(a, b):
    return math.sqrt((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2)

def get_routing_neighbors():
    online = get_online_peers()
    if online:
        nbrs = list(online.keys())
    else:
        nbrs = config.get("neighbors", {}).get(NODE_ID, [])
    static = config.get("neighbors", {}).get(NODE_ID, [])
    if BANK_NODE_ID in static and BANK_NODE_ID not in nbrs:
        nbrs.append(BANK_NODE_ID)
    return nbrs

def angle(from_pos, to_pos):
    return math.atan2(to_pos["y"] - from_pos["y"], to_pos["x"] - from_pos["x"])

def angle_diff(target, source):
    diff = target - source
    if diff < 0:
        diff += 2 * math.pi
    return diff

def greedy_next_hop(dest_id):
    global current_routing_mode, last_routing_decision
    best = None
    best_dist = float("inf")
    dest_pos = nodes_map[dest_id]
    nbr_ids = get_routing_neighbors()
    for nid in nbr_ids:
        if nid not in nodes_map:
            continue
        d = distance(nodes_map[nid], dest_pos)
        if d < best_dist:
            best_dist = d
            best = nid
    if best is not None:
        current_routing_mode = "GREEDY"
        last_routing_decision = {
            "chosenNeighbor": best,
            "reason": "GREEDY \u2014 closest to bank"
        }
    return best, best_dist

def perimeter_next_hop(prev_hop):
    global current_routing_mode, last_routing_decision
    my_p = nodes_map[NODE_ID]
    nbr_ids = get_routing_neighbors()
    if not nbr_ids:
        return None
    if prev_hop is None:
        ref_angle = angle(my_p, bank_pos)
    else:
        ref_pos = nodes_map.get(prev_hop, bank_pos)
        ref_angle = angle(my_p, ref_pos)
    nbrs = []
    for nid in nbr_ids:
        if nid not in nodes_map:
            continue
        a = angle(my_p, nodes_map[nid])
        diff = angle_diff(a, ref_angle)
        nbrs.append((nid, diff))
    nbrs.sort(key=lambda x: x[1])
    if nbrs:
        chosen = nbrs[0][0]
        current_routing_mode = "PERIMETER"
        last_routing_decision = {
            "chosenNeighbor": chosen,
            "reason": "PERIMETER \u2014 right-hand rule"
        }
        return chosen
    return None

def report_to_visualizer(data):
    if not VISUALIZER_URL:
        return
    try:
        requests.post(f"{VISUALIZER_URL}/hop", json=data, timeout=2)
    except:
        pass

@app.route('/receive', methods=['POST'])
def receive_packet():
    packet = request.json
    packet_id = packet.get('packetId', 'UNKNOWN')
    ttl = packet.get('ttl', 0)
    mode = packet.get('mode', 'greedy')
    l_x = packet.get('l_x')
    l_y = packet.get('l_y')
    prev_hop = packet.get('prev_hop')
    first_edge_start = packet.get('first_edge_start')
    first_edge_end = packet.get('first_edge_end')

    print(f"\n[{NODE_ID}] Received packet! TTL={ttl} mode={mode}")
    report_to_visualizer({
        "node_id": NODE_ID, "packet_id": packet_id,
        "action": "RECEIVED", "ttl": ttl,
        "message": f"{NODE_ID} received ({mode})",
        "timestamp": int(time.time() * 1000)
    })

    if ttl <= 0:
        print(f"[{NODE_ID}] Dropping: TTL expired!")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "DROPPED", "ttl": ttl,
            "message": f"{NODE_ID} dropped: TTL expired",
            "timestamp": int(time.time() * 1000)
        })
        return jsonify({"error": "TTL Expired"}), 400

    packet['ttl'] = ttl - 1
    self_dist = distance(my_pos, bank_pos)

    routing_history.append({
        "packetId": packet_id,
        "selfDistToBank": round(self_dist, 1),
        "mode": current_routing_mode,
        "timestamp": int(time.time() * 1000)
    })
    if len(routing_history) > 50:
        routing_history.pop(0)

    target = None

    if mode == 'greedy':
        target, target_dist = greedy_next_hop(BANK_NODE_ID)

        if target is not None and target_dist < self_dist:
            pass
        else:
            print(f"[{NODE_ID}] Greedy FAILED \u2014 switching to perimeter mode")
            report_to_visualizer({
                "node_id": NODE_ID, "packet_id": packet_id,
                "action": "PERIMETER", "ttl": packet['ttl'],
                "message": f"{NODE_ID} local max, entering perimeter mode",
                "timestamp": int(time.time() * 1000)
            })

            packet['mode'] = 'perimeter'
            packet['l_x'] = my_pos["x"]
            packet['l_y'] = my_pos["y"]
            packet['routingMode'] = 'PERIMETER'

            target = perimeter_next_hop(None)
            if target is None:
                print(f"[{NODE_ID}] No neighbors for perimeter routing")
                report_to_visualizer({
                    "node_id": NODE_ID, "packet_id": packet_id,
                    "action": "DROPPED", "ttl": packet['ttl'],
                    "message": f"{NODE_ID} no neighbors",
                    "timestamp": int(time.time() * 1000)
                })
                return jsonify({"status": "No neighbors"}), 200

            packet['first_edge_start'] = NODE_ID
            packet['first_edge_end'] = target
    else:
        if l_x is None or l_y is None:
            print(f"[{NODE_ID}] Perimeter mode but no L point \u2014 falling back to greedy")
            packet['mode'] = 'greedy'
            packet['routingMode'] = 'GREEDY'
            target, _ = greedy_next_hop(BANK_NODE_ID)
        else:
            l_point = {"x": l_x, "y": l_y}
            self_to_bank = self_dist
            l_to_bank = distance(l_point, bank_pos)

            if self_to_bank < l_to_bank:
                print(f"[{NODE_ID}] Perimeter \u2192 Greedy (closer to bank than L)")
                report_to_visualizer({
                    "node_id": NODE_ID, "packet_id": packet_id,
                    "action": "GREEDY_RESTORE", "ttl": packet['ttl'],
                    "message": f"{NODE_ID} closer to bank than L, restoring greedy",
                    "timestamp": int(time.time() * 1000)
                })
                packet['mode'] = 'greedy'
                packet['routingMode'] = 'GREEDY'
                target, _ = greedy_next_hop(BANK_NODE_ID)
            else:
                target = perimeter_next_hop(prev_hop)

                if (NODE_ID == first_edge_start and target == first_edge_end and
                        first_edge_start is not None):
                    print(f"[{NODE_ID}] Face exhausted \u2014 destination unreachable")
                    report_to_visualizer({
                        "node_id": NODE_ID, "packet_id": packet_id,
                        "action": "DROPPED", "ttl": packet['ttl'],
                        "message": f"{NODE_ID} destination unreachable (face exhausted)",
                        "timestamp": int(time.time() * 1000)
                    })
                    return jsonify({"status": "Destination unreachable"}), 200

    packet['routingMode'] = current_routing_mode

    if target is None:
        print(f"[{NODE_ID}] End of line \u2014 no neighbors")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "END_OF_LINE", "ttl": packet['ttl'],
            "message": f"{NODE_ID} no neighbors",
            "timestamp": int(time.time() * 1000)
        })
        return jsonify({"status": "End of the line"}), 200

    packet['prev_hop'] = NODE_ID

    if target == BANK_NODE_ID or target == "Bank":
        print(f"   -> Forwarding DIRECTLY to Bank!")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "FORWARDED", "ttl": packet['ttl'],
            "to_node": "Bank",
            "message": f"{NODE_ID} \u2192 Bank (gateway)",
            "timestamp": int(time.time() * 1000)
        })
        try:
            response = requests.post(BANK_URL, json=packet, timeout=15)
            report_to_visualizer({
                "node_id": "Bank", "packet_id": packet_id,
                "action": "RECEIVED", "ttl": packet['ttl'],
                "message": "Bank received via mesh",
                "timestamp": int(time.time() * 1000)
            })
            bank_action = "SETTLED" if response.ok else "REJECTED"
            report_to_visualizer({
                "node_id": "Bank", "packet_id": packet_id,
                "action": bank_action, "ttl": packet['ttl'],
                "message": f"Bank {bank_action.lower()}: HTTP {response.status_code}",
                "timestamp": int(time.time() * 1000)
            })
            try:
                next_response = response.json()
            except:
                next_response = response.text
            return jsonify({
                "status": f"Forwarded by {NODE_ID} to Bank",
                "downstream_response": next_response
            }), response.status_code
        except Exception as e:
            print(f"[{NODE_ID}] Failed to reach Bank: {e}")
            queue_packet(packet, BANK_URL, "Bank")
            return jsonify({"status": f"Queued by {NODE_ID} for offline delivery to Bank"}), 202
    else:
        neighbor_url = node_urls[target] + "/receive"
        mode_label = packet.get('mode', 'greedy')
        print(f"   {mode_label}: {NODE_ID} \u2192 {target}")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "FORWARDED", "ttl": packet['ttl'],
            "to_node": target,
            "message": f"{NODE_ID} \u2192 {target} ({mode_label})",
            "timestamp": int(time.time() * 1000)
        })
        try:
            response = requests.post(neighbor_url, json=packet, timeout=15)
            try:
                next_response = response.json()
            except:
                next_response = response.text
            return jsonify({
                "status": f"Forwarded by {NODE_ID} to {target}",
                "greedy_choice": target,
                "downstream_response": next_response
            }), response.status_code
        except Exception as e:
            print(f"[{NODE_ID}] Failed to reach {target}: {e}")
            report_to_visualizer({
                "node_id": NODE_ID, "packet_id": packet_id,
                "action": "QUEUED", "ttl": packet['ttl'],
                "message": f"{NODE_ID} queued offline for {target}",
                "timestamp": int(time.time() * 1000)
            })
            queue_packet(packet, neighbor_url, target)
            return jsonify({
                "status": f"Queued by {NODE_ID} for offline delivery to {target}",
                "greedy_choice": target
            }), 202

@app.route('/offline-status', methods=['GET'])
def offline_status():
    with offline_queue_lock:
        q_info = []
        for entry in offline_queue:
            q_info.append({
                "targetNode": entry["target_node"],
                "attempts": entry["attempts"],
                "nextRetryIn": max(0, round(entry["next_retry"] - time.time(), 1)),
                "enqueuedAt": round(entry["enqueued_at"], 1),
                "packetId": entry["packet"].get("packetId", "UNKNOWN")
            })
    return jsonify({
        "nodeId": NODE_ID,
        "queuedPackets": len(offline_queue),
        "queueDetails": q_info,
        "discoveredPeers": get_discovered_peer_ids(),
        "currentRoutingMode": current_routing_mode,
        "lastRoutingDecision": last_routing_decision
    })

@app.route('/debug', methods=['GET'])
def debug():
    target, target_dist = greedy_next_hop(BANK_NODE_ID)
    online_peers = get_online_peers()
    details = []
    for nid, info in online_peers.items():
        d = round(distance(nodes_map[nid], bank_pos), 1) if nid in nodes_map else None
        best_flag = (nid == target)
        details.append({
            "neighbor": nid, "distanceToBank": d,
            "online": info["online"], "lastSeen": round(info["last_seen"], 1),
            "isBestGreedy": best_flag
        })
    return jsonify({
        "nodeId": NODE_ID,
        "position": my_pos,
        "discoveredPeers": get_discovered_peer_ids(),
        "allPeers": {pid: {"online": info["online"], "lastSeen": round(info["last_seen"], 1)}
                     for pid, info in discovered_peers.items()},
        "bankPosition": bank_pos,
        "selfDistanceToBank": round(distance(my_pos, bank_pos), 1),
        "greedyTarget": target,
        "greedyTargetDist": round(target_dist, 1) if target else None,
        "neighborDetails": details,
        "currentRoutingMode": current_routing_mode,
        "lastRoutingDecision": last_routing_decision,
        "routingHistory": routing_history[-20:]
    })

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "nodeId": NODE_ID, "routingMode": current_routing_mode})

if __name__ == '__main__':
    print(f"\n[{NODE_ID}] GPSR mesh node (BLE discovery + greedy/perimeter routing)")
    print(f"   Position: {my_pos}")
    print(f"   BLE discovery range: {BLE_DISCOVERY_RANGE}")
    print(f"   Bank pos: {bank_pos}")
    app.run(host='0.0.0.0', port=5001)
