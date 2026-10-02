#!/usr/bin/env python3
import http.client, ssl, json, os, sys, base64

CTX = ssl.create_default_context()
TOKEN = os.environ.get("GH_TOKEN", "")

def req(method, path, body=None, host="api.github.com"):
    c = http.client.HTTPSConnection(host, timeout=30, context=CTX)
    headers = {
        "User-Agent": "LiquidFrame-CI",
        "Accept": "application/vnd.github+json",
    }
    if TOKEN:
        headers["Authorization"] = "token " + TOKEN
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    c.request(method, path, body=data, headers=headers)
    r = c.getresponse()
    raw = r.read()
    try:
        j = json.loads(raw)
    except Exception:
        j = raw[:500]
    return r.status, j

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "status":
        s, d = req("GET", "/repos/mouya-q/LiquidFrame/commits?per_page=5")
        print("commits:", s)
        if isinstance(d, list):
            for c in d:
                print(" ", c["sha"][:12], c["commit"]["message"].splitlines()[0])
        s, d = req("GET", "/repos/mouya-q/LiquidFrame/actions/runs?per_page=5")
        print("runs:", s)
        if isinstance(d, dict):
            for r in d.get("workflow_runs", []):
                print(" ", r["id"], r["name"], r["status"], r["conclusion"], r["head_sha"][:12])
