#!/usr/bin/env python3
"""Push the LiquidFrame working tree to GitHub via the Git Data API.

Uses urllib (not http.client) because the proot container's http.client SSL
handshakes intermittently fail with SSLEOFError / RemoteDisconnected. Adds
retry + backoff around every API call.
"""
import base64
import json
import os
import ssl
import sys
import time
import urllib.request
import urllib.error

TOK = os.environ.get("GH_TOKEN", "")
CTX = ssl._create_unverified_context()
OWNER = "mouya-q"
REPO = "LiquidFrame"
BRANCH = "main"
ROOT = os.environ.get("LF_ROOT", "/root/LiquidFrame")

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "tools"}
MAX_RETRIES = 5
BASE_DELAY = 3.0


def api(method, path, body=None, timeout=120):
    url = "https://api.github.com" + path
    data = None
    headers = {"User-Agent": "LiquidFrame-Push", "Accept": "application/vnd.github+json"}
    if TOK:
        headers["Authorization"] = "token " + TOK
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"

    for attempt in range(1, MAX_RETRIES + 1):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            resp = urllib.request.urlopen(req, timeout=timeout, context=CTX)
            raw = resp.read()
            try:
                return resp.status, json.loads(raw)
            except Exception:
                return resp.status, raw[:400]
        except urllib.error.HTTPError as e:
            # 4xx are non-retryable
            if 400 <= e.code < 500:
                return e.code, e.read()[:300]
            delay = BASE_DELAY * attempt
            print(f"  retry {attempt}/{MAX_RETRIES} {method} {path} HTTP {e.code} in {delay:.0f}s")
            time.sleep(delay)
        except Exception as e:
            delay = BASE_DELAY * attempt
            print(f"  retry {attempt}/{MAX_RETRIES} {method} {path} {str(e)[:60]} in {delay:.0f}s")
            time.sleep(delay)
    raise RuntimeError(f"api failed after {MAX_RETRIES} attempts: {method} {path}")


def collect_files():
    found = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            found[rel] = full
    return found


def main():
    message = sys.argv[1] if len(sys.argv) > 1 else "fix: repair gradle and CI config"

    status, ref = api("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")
    if status != 200:
        print("ref error", status, ref)
        return 1
    base_sha = ref["object"]["sha"]
    print("base commit:", base_sha[:12])

    status, commit = api("GET", f"/repos/{OWNER}/{REPO}/git/commits/{base_sha}")
    if status != 200:
        print("commit error", status, commit)
        return 1
    base_tree = commit["tree"]["sha"]
    print("base tree:", base_tree[:12])

    files = collect_files()
    print("files:", len(files))

    entries = []
    for i, (rel, full) in enumerate(sorted(files.items()), 1):
        with open(full, "rb") as fh:
            content = fh.read()
        status, blob = api(
            "POST",
            f"/repos/{OWNER}/{REPO}/git/blobs",
            {"content": base64.b64encode(content).decode(), "encoding": "base64"},
        )
        if status != 201:
            print("blob error", rel, status, blob)
            return 1
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"  blob {i}/{len(files)} {rel}")

    status, new_tree = api(
        "POST",
        f"/repos/{OWNER}/{REPO}/git/trees",
        {"base_tree": base_tree, "tree": entries},
    )
    if status != 201:
        print("tree error", status, new_tree)
        return 1
    print("new tree:", new_tree["sha"][:12])

    status, new_commit = api(
        "POST",
        f"/repos/{OWNER}/{REPO}/git/commits",
        {"message": message, "tree": new_tree["sha"], "parents": [base_sha]},
    )
    if status != 201:
        print("commit create error", status, new_commit)
        return 1
    print("new commit:", new_commit["sha"][:12])

    status, res = api(
        "PATCH",
        f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
        {"sha": new_commit["sha"], "force": True},
    )
    print("update ref:", status)
    if status not in (200, 201):
        print(res)
        return 1
    print("PUSHED", new_commit["sha"])
    return 0


if __name__ == "__main__":
    sys.exit(main())