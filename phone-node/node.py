import os, json, math, time, requests
from flask import Flask, request, jsonify

CONFIG_PATH = "/app/mesh-topology.json"

app = Flask(__name__)

NODE_ID = os.environ.get("NODE_ID", "Unknown-Phone")
VISUALIZER_URL = os.environ.get("VISUALIZER_URL")

with open(CONFIG_PATH) as f:
    config = json.load(f)

nodes_map = {n["id"]: n for n in config["nodes"]}
neighbors_map = config["neighbors"]
node_urls = config["nodeUrls"]
BANK_URL = config["bankUrl"]
BANK_NODE_ID = config["bankNodeId"]
bank_pos = nodes_map[BANK_NODE_ID]

my_pos = nodes_map[NODE_ID]
my_neighbor_ids = neighbors_map.get(NODE_ID, [])

routing_history = []

# ---- GPSR: RNG Planar Subgraph ----
rng_cache = {}

def distance(a, b):
    return math.sqrt((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2)

def compute_rng():
    for u_id in nodes_map:
        u_rng = []
        pos_u = nodes_map[u_id]
        for v_id in neighbors_map.get(u_id, []):
            pos_v = nodes_map[v_id]
            d_uv = distance(pos_u, pos_v)
            keep = True
            for w_id, w_pos in nodes_map.items():
                if w_id == u_id or w_id == v_id:
                    continue
                if distance(pos_u, w_pos) < d_uv and distance(pos_v, w_pos) < d_uv:
                    keep = False
                    break
            if keep:
                u_rng.append(v_id)
        rng_cache[u_id] = u_rng

compute_rng()

def angle(from_pos, to_pos):
    return math.atan2(to_pos["y"] - from_pos["y"], to_pos["x"] - from_pos["x"])

def angle_diff(target, source):
    diff = target - source
    if diff < 0:
        diff += 2 * math.pi
    return diff

def greedy_next_hop(dest_id):
    best = None
    best_dist = float("inf")
    dest_pos = nodes_map[dest_id]
    for nid in my_neighbor_ids:
        d = distance(nodes_map[nid], dest_pos)
        if d < best_dist:
            best_dist = d
            best = nid
    return best, best_dist

def perimeter_next_hop(prev_hop):
    rng_nbrs = rng_cache.get(NODE_ID, [])
    if not rng_nbrs:
        return None

    my_p = nodes_map[NODE_ID]

    if prev_hop is None:
        ref_angle = angle(my_p, nodes_map[BANK_NODE_ID])
    else:
        ref_angle = angle(my_p, nodes_map[prev_hop])

    nbrs = [(nid, angle(my_p, nodes_map[nid])) for nid in rng_nbrs]
    nbrs.sort(key=lambda x: x[1])

    for nid, a in nbrs:
        if angle_diff(a, ref_angle) > 1e-10:
            return nid

    return nbrs[0][0] if nbrs else None

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
    target = None

    routing_history.append({
        "packetId": packet_id,
        "selfDistToBank": round(self_dist, 1),
        "mode": mode,
        "timestamp": int(time.time() * 1000)
    })
    if len(routing_history) > 50:
        routing_history.pop(0)

    if mode == 'greedy':
        target, target_dist = greedy_next_hop(BANK_NODE_ID)

        if target is not None and target_dist < self_dist:
            pass
        else:
            print(f"[{NODE_ID}] Greedy FAILED — switching to perimeter mode")
            report_to_visualizer({
                "node_id": NODE_ID, "packet_id": packet_id,
                "action": "PERIMETER", "ttl": packet['ttl'],
                "message": f"{NODE_ID} local max, entering perimeter mode",
                "timestamp": int(time.time() * 1000)
            })

            packet['mode'] = 'perimeter'
            packet['l_x'] = my_pos["x"]
            packet['l_y'] = my_pos["y"]

            target = perimeter_next_hop(None)
            if target is None:
                print(f"[{NODE_ID}] No RNG neighbors for perimeter routing")
                report_to_visualizer({
                    "node_id": NODE_ID, "packet_id": packet_id,
                    "action": "DROPPED", "ttl": packet['ttl'],
                    "message": f"{NODE_ID} no RNG neighbors",
                    "timestamp": int(time.time() * 1000)
                })
                return jsonify({"status": "No RNG neighbors"}), 200

            packet['first_edge_start'] = NODE_ID
            packet['first_edge_end'] = target
    else:
        if l_x is None or l_y is None:
            print(f"[{NODE_ID}] Perimeter mode but no L point — falling back to greedy")
            packet['mode'] = 'greedy'
            target, _ = greedy_next_hop(BANK_NODE_ID)
        else:
            l_point = {"x": l_x, "y": l_y}
            self_to_bank = self_dist
            l_to_bank = distance(l_point, bank_pos)

            if self_to_bank < l_to_bank:
                print(f"[{NODE_ID}] Perimeter → Greedy (closer to bank than L)")
                report_to_visualizer({
                    "node_id": NODE_ID, "packet_id": packet_id,
                    "action": "GREEDY_RESTORE", "ttl": packet['ttl'],
                    "message": f"{NODE_ID} closer to bank than L, restoring greedy",
                    "timestamp": int(time.time() * 1000)
                })
                packet['mode'] = 'greedy'
                target, _ = greedy_next_hop(BANK_NODE_ID)
            else:
                target = perimeter_next_hop(prev_hop)

                if (NODE_ID == first_edge_start and target == first_edge_end and
                        first_edge_start is not None):
                    print(f"[{NODE_ID}] Face exhausted — destination unreachable")
                    report_to_visualizer({
                        "node_id": NODE_ID, "packet_id": packet_id,
                        "action": "DROPPED", "ttl": packet['ttl'],
                        "message": f"{NODE_ID} destination unreachable (face exhausted)",
                        "timestamp": int(time.time() * 1000)
                    })
                    return jsonify({"status": "Destination unreachable"}), 200

    if target is None:
        print(f"[{NODE_ID}] End of line — no neighbors")
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
            "message": f"{NODE_ID} → Bank (gateway)",
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
            return jsonify({"error": str(e)}), 500
    else:
        neighbor_url = node_urls[target] + "/receive"
        mode_label = packet.get('mode', 'greedy')
        print(f"   {mode_label}: {NODE_ID} → {target}")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "FORWARDED", "ttl": packet['ttl'],
            "to_node": target,
            "message": f"{NODE_ID} → {target} ({mode_label})",
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
            return jsonify({"error": str(e)}), 500

@app.route('/debug', methods=['GET'])
def debug():
    target, target_dist = greedy_next_hop(BANK_NODE_ID)
    details = []
    for nid in my_neighbor_ids:
        d = round(distance(nodes_map[nid], bank_pos), 1)
        rng = nid in rng_cache.get(NODE_ID, [])
        best_flag = (nid == target)
        details.append({
            "neighbor": nid, "distanceToBank": d,
            "rng": rng, "isBestGreedy": best_flag
        })
    return jsonify({
        "nodeId": NODE_ID,
        "position": my_pos,
        "neighbors": my_neighbor_ids,
        "rngNeighbors": rng_cache.get(NODE_ID, []),
        "bankPosition": bank_pos,
        "selfDistanceToBank": round(distance(my_pos, bank_pos), 1),
        "greedyTarget": target,
        "greedyTargetDist": round(target_dist, 1) if target else None,
        "neighborDetails": details,
        "routingHistory": routing_history[-20:]
    })

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "nodeId": NODE_ID})

if __name__ == '__main__':
    print(f"\n[{NODE_ID}] GPSR mesh node (greedy + perimeter/face routing)")
    print(f"   Position: {my_pos}")
    print(f"   Neighbors: {my_neighbor_ids}")
    print(f"   RNG neighbors: {rng_cache.get(NODE_ID, [])}")
    print(f"   Bank pos: {bank_pos}")
    app.run(host='0.0.0.0', port=5001)
