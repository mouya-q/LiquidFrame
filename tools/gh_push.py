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

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "tools", ".backup"}
SKIP_NAMES = {".DS_Store", "local.properties", "push_out.log"}
# Remote paths that must never exist in the repository (privacy / noise).
PURGE_PREFIXES = (".backup/", "app/build/", ".gradle/", ".idea/")
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
            if name in SKIP_NAMES:
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            found[rel] = full
    return found


def collect_purges(tree_sha):
    """Return remote paths under PURGE_PREFIXES that must be deleted."""
    status, tree = api(
        "GET", f"/repos/{OWNER}/{REPO}/git/trees/{tree_sha}?recursive=1"
    )
    if status != 200 or not isinstance(tree, dict):
        return []
    out = []
    for item in tree.get("tree", []):
        path = item.get("path", "")
        if item.get("type") != "blob":
            continue
        if any(path.startswith(p) for p in PURGE_PREFIXES):
            out.append(path)
    return out


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

    # LF_REBASE_FROM lets us rewrite the tip commit: the new commit is built from
    # that sha's tree and points at it as parent, so a bad commit (e.g. one that
    # leaked .backup/ files) drops out of the branch history.
    rebase_from = os.environ.get("LF_REBASE_FROM", "").strip()
    if rebase_from:
        status, parent_commit = api("GET", f"/repos/{OWNER}/{REPO}/git/commits/{rebase_from}")
        if status != 200:
            print("rebase parent error", status, parent_commit)
            return 1
        base_sha = rebase_from
        base_tree = parent_commit["tree"]["sha"]
        print("rebase from:", base_sha[:12], "tree:", base_tree[:12])

    # Delete stale remote paths (chat backups etc.) by adding sha=None entries.
    tree_status, base_tree_full = api(
        "GET", f"/repos/{OWNER}/{REPO}/git/trees/{base_tree}?recursive=1"
    )
    base_tree_ok = tree_status == 200 and isinstance(base_tree_full, dict)
    purge = []
    if base_tree_ok:
        for item in base_tree_full.get("tree", []):
            p = item.get("path", "")
            if item.get("type") == "blob" and any(p.startswith(x) for x in PURGE_PREFIXES):
                purge.append(p)
    if purge:
        print("purging", len(purge), "stale remote paths")

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

    # Delete remote blobs that no longer exist locally. Without this a locally deleted file
    # stays in the tree forever and its declarations keep colliding with the new ones
    # (duplicate-symbol errors on the next build).
    local_paths = set(files.keys())
    orphans = []
    if base_tree_ok:
        for item in base_tree_full.get("tree", []):
            p = item.get("path", "")
            if item.get("type") != "blob" or not p:
                continue
            if any(p.startswith(x) for x in PURGE_PREFIXES):
                continue  # already queued for purge
            if p not in local_paths:
                orphans.append(p)
    if orphans:
        print("removing", len(orphans), "remote orphans")
        for p in orphans:
            entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})

    # sha=None removes a path from the resulting tree.
    for p in purge:
        entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})

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