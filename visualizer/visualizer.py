import os, json, time, threading
from flask import Flask, request, jsonify, render_template, Response

app = Flask(__name__)

hop_events = []
lock = threading.Lock()

TOPOLOGY = {
    "nodes": ["Node-1", "Node-2", "Node-3", "Node-4", "Node-5",
              "Node-6", "Node-7", "Node-8", "Node-9", "Node-10", "Bank"],
    "edges": [
        {"from": "Node-1", "to": "Node-2"},
        {"from": "Node-1", "to": "Node-4"},
        {"from": "Node-1", "to": "Node-5", "dashed": True},
        {"from": "Node-2", "to": "Node-3"},
        {"from": "Node-2", "to": "Node-4", "dashed": True},
        {"from": "Node-2", "to": "Node-5"},
        {"from": "Node-2", "to": "Node-6", "dashed": True},
        {"from": "Node-3", "to": "Node-5", "dashed": True},
        {"from": "Node-3", "to": "Node-6"},
        {"from": "Node-4", "to": "Node-5"},
        {"from": "Node-4", "to": "Node-7"},
        {"from": "Node-4", "to": "Node-8", "dashed": True},
        {"from": "Node-5", "to": "Node-6"},
        {"from": "Node-5", "to": "Node-7", "dashed": True},
        {"from": "Node-5", "to": "Node-8"},
        {"from": "Node-5", "to": "Node-9", "dashed": True},
        {"from": "Node-6", "to": "Node-8", "dashed": True},
        {"from": "Node-6", "to": "Node-9"},
        {"from": "Node-7", "to": "Node-8"},
        {"from": "Node-8", "to": "Node-9"},
        {"from": "Node-9", "to": "Node-10"},
        {"from": "Node-10", "to": "Bank"},
    ],
    "positions": {
        "Node-1":  {"x": 0,   "y": 0},
        "Node-2":  {"x": 150, "y": 0},
        "Node-3":  {"x": 300, "y": 0},
        "Node-4":  {"x": 0,   "y": 150},
        "Node-5":  {"x": 150, "y": 150},
        "Node-6":  {"x": 300, "y": 150},
        "Node-7":  {"x": 0,   "y": 300},
        "Node-8":  {"x": 150, "y": 300},
        "Node-9":  {"x": 300, "y": 300},
        "Node-10": {"x": 400, "y": 300},
        "Bank":    {"x": 400, "y": 450},
    }
}

@app.route('/')
def index():
    return render_template('dashboard.html', topology=json.dumps(TOPOLOGY))

@app.route('/hop', methods=['POST'])
def record_hop():
    data = request.json
    with lock:
        hop_events.append(data)
    return jsonify({"ok": True})

@app.route('/events')
def event_stream():
    def generate():
        idx = 0
        while True:
            with lock:
                n = len(hop_events)
                while idx < n:
                    yield f"data: {json.dumps(hop_events[idx])}\n\n"
                    idx += 1
            time.sleep(0.25)
    return Response(generate(), mimetype='text/event-stream')

@app.route('/reset', methods=['POST'])
def reset():
    with lock:
        hop_events.clear()
    return jsonify({"ok": True})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5002)
