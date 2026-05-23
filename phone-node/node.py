import os, requests
from flask import Flask, request, jsonify

app = Flask(__name__)

# Fetch environment variables set by Docker Compose
NODE_ID = os.environ.get("NODE_ID", "Unknown-Phone")
NEXT_NODE_URL = os.environ.get("NEXT_NODE_URL")

@app.route('/receive', methods=['POST'])
def receive_packet():
    packet = request.json
    print(f"\n📱 [{NODE_ID}] Received Mesh Packet!")

    # 1. The Zombie Defense: Check TTL
    if packet.get('ttl', 0) <= 0:
        print(f"❌ [{NODE_ID}] Dropping Packet: TTL expired!")
        return jsonify({"error": "TTL Expired"}), 400

    # 2. Decrement TTL (The Hop)
    packet['ttl'] -= 1
    print(f"   -> Hop successful. TTL decremented to {packet['ttl']}")

    # 3. Forward to the next node in the mesh
    if NEXT_NODE_URL:
        print(f"   -> Throwing packet to {NEXT_NODE_URL}...")
        try:
            # Send it to the next phone (or the Spring Boot Bank)
            response = requests.post(NEXT_NODE_URL, json=packet)

            # Read the response
            try:
                next_response = response.json()
            except:
                next_response = response.text

            return jsonify({
                "status": f"Successfully forwarded by {NODE_ID}",
                "downstream_response": next_response
            }), response.status_code
        except Exception as e:
            print(f"❌ [{NODE_ID}] Failed to reach next node: {e}")
            return jsonify({"error": str(e)}), 500
    else:
        return jsonify({"status": "End of the line"}), 200

if __name__ == '__main__':
    # Run the server on port 5001 (to avoid Mac AirTunes conflict)
    app.run(host='0.0.0.0', port=5001)