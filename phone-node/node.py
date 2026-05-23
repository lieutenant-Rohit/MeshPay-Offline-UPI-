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

def distance(a, b):
    return math.sqrt((a["x"] - b["x"]) ** 2 + (a["y"] - b["y"]) ** 2)

def best_gateway():
    best = None
    best_dist = float("inf")
    details = []
    for nid in my_neighbor_ids:
        pos = nodes_map[nid]
        d = distance(pos, bank_pos)
        details.append({"neighbor": nid, "position": pos, "distanceToBank": round(d, 1)})
        if d < best_dist:
            best_dist = d
            best = nid
    return best, best_dist, details

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

    print(f"\n[{NODE_ID}] Received Mesh Packet! TTL={ttl}")
    report_to_visualizer({
        "node_id": NODE_ID, "packet_id": packet_id,
        "action": "RECEIVED", "ttl": ttl,
        "message": f"{NODE_ID} received packet, TTL={ttl}",
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

    packet['ttl'] -= 1

    target, dist, details = best_gateway()
    self_dist = distance(my_pos, bank_pos)

    routing_history.append({
        "packetId": packet_id,
        "selfDistToBank": round(self_dist, 1),
        "neighborOptions": details,
        "chosenGateway": target,
        "chosenDistToBank": round(dist, 1) if target else None,
        "timestamp": int(time.time() * 1000)
    })
    if len(routing_history) > 50:
        routing_history.pop(0)

    print(f"   Self dist to Bank: {self_dist:.1f}")
    print(f"   Neighbor options: {[d['neighbor'] + '(' + str(d['distanceToBank']) + ')' for d in details]}")

    if target is None:
        print(f"[{NODE_ID}] End of line — no neighbors")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "END_OF_LINE", "ttl": packet['ttl'],
            "message": f"{NODE_ID} no neighbors to forward to",
            "timestamp": int(time.time() * 1000)
        })
        return jsonify({"status": "End of the line"}), 200

    if target == BANK_NODE_ID or target == "Bank":
        print(f"   -> Forwarding DIRECTLY to Bank (gateway)!")
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
                "message": "Bank received packet via mesh",
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
        print(f"   Greedy routing: {NODE_ID} → {target} (dist to Bank: {dist:.1f})")
        report_to_visualizer({
            "node_id": NODE_ID, "packet_id": packet_id,
            "action": "FORWARDED", "ttl": packet['ttl'],
            "to_node": target,
            "message": f"{NODE_ID} → {target} (greedy)",
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
    _, _, details = best_gateway()
    return jsonify({
        "nodeId": NODE_ID,
        "position": my_pos,
        "neighbors": my_neighbor_ids,
        "bankPosition": bank_pos,
        "selfDistanceToBank": round(distance(my_pos, bank_pos), 1),
        "neighborDistancesToBank": details,
        "routingHistory": routing_history[-20:]
    })

@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "nodeId": NODE_ID})

if __name__ == '__main__':
    print(f"\n[{NODE_ID}] Greedy geographic mesh node")
    print(f"   Position: {my_pos}")
    print(f"   Neighbors: {my_neighbor_ids}")
    print(f"   Bank pos: {bank_pos}")
    app.run(host='0.0.0.0', port=5001)
