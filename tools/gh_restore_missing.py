"""Recover files dropped by a full-tree push, byte-for-byte from the remote history.

Why this exists
---------------
ghpush.py replaces the remote branch's entire tree with the local directory. That is
the wrong tool when the local directory has *lost* files the remote still had: the
push then deletes them from the remote too. That is what happened to
.github/workflows/build.yml - the local workspace had no .github directory, the
remote did, and the push removed the original CI file and replaced it with a rewrite
that then took two more runs to go green.

Run:  python3 tools/gh_restore_missing.py <repo> <ref> [--dry] [<path>...]
  ref       the commit whose tree is the source of truth (the last green one here)
  paths     when given, these files are restored from the ref even though they exist
            locally - use this to reclaim a file that was overwritten, not deleted

Read-only with --dry. Without it, remote-only files are restored, plus any explicitly
listed paths, always byte-for-byte from the ref.
"""

import base64
import hashlib
import json
import os
import ssl
import sys
import urllib.parse
import urllib.request

API = "https://api.github.com"
HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_CANDIDATES = [
    os.path.join(HERE, "..", "..", "push", ".gh_token"),
    os.path.join(HERE, ".gh_token"),
]

# Keep in sync with push/ghpush.py: the remote tree only ever contains what these
# rules let through, so the local set must be computed the same way or the diff
# reports files that were never pushed in the first place.
EXCLUDE_DIRS = {".git", ".gradle", ".kotlin", ".idea", ".androidide", ".backup",
                "build", ".cxx", "__pycache__", ".vscode", ".fleet"}
EXCLUDE_SUFFIX = {".apk", ".aab", ".jks", ".keystore", ".iml", ".o", ".so", ".d", ".class"}
EXCLUDE_NAMES = {"CONTRIBUTING.md", ".DS_Store", "Thumbs.db", "local.properties"}

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def token():
    for path in TOKEN_CANDIDATES:
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8") as fh:
                return fh.read().strip()
    raise SystemExit("no .gh_token found")


def req(path):
    request = urllib.request.Request(
        API + path,
        headers={
            "Authorization": "Bearer " + token(),
            "User-Agent": "gh-restore",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request, context=CTX, timeout=90) as resp:
        return json.loads(resp.read().decode("utf-8") or "{}")


def git_blob_sha(path):
    """The same blob id ghpush.py uploads, computed locally for comparison."""
    with open(path, "rb") as fh:
        content = fh.read()
    digest = hashlib.sha1(b"blob %d\0" % len(content))
    digest.update(content)
    return digest.hexdigest()


def local_files(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in EXCLUDE_DIRS)
        for name in filenames:
            if name in EXCLUDE_NAMES or os.path.splitext(name)[1] in EXCLUDE_SUFFIX:
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            out[rel] = full
    return out


def remote_blobs(repo, ref):
    data = req("/repos/%s/git/trees/%s?recursive=1"
               % (repo, urllib.parse.quote(ref, safe="")))
    if data.get("truncated"):
        raise SystemExit("tree truncated at %s; paginate before trusting this diff" % ref)
    return {i["path"]: i["sha"] for i in data.get("tree", []) if i.get("type") == "blob"}


def fetch_blob(repo, ref, path):
    data = req("/repos/%s/contents/%s?ref=%s"
               % (repo, urllib.parse.quote(path, safe="/"),
                  urllib.parse.quote(ref, safe="")))
    return base64.b64decode(data["content"])


def main():
    argv = sys.argv[1:]
    dry = "--dry" in argv
    args = [a for a in argv if a != "--dry"]
    if len(args) < 2:
        raise SystemExit(__doc__)
    repo, ref = args[0], args[1]
    forced = args[2:]
    root = os.path.abspath(os.path.join(HERE, ".."))

    local = local_files(root)
    remote = remote_blobs(repo, ref)

    missing = sorted(set(remote) - set(local))
    differing = []
    for rel, full in sorted(local.items()):
        want = remote.get(rel)
        if want is not None and git_blob_sha(full) != want:
            differing.append(rel)

    print("ref %s: %d remote blobs, %d local files" % (ref, len(remote), len(local)))
    print("\nremote-only (would have been dropped by a full-tree push):")
    for rel in missing:
        print("  " + rel)
    print("\nboth sides, content differs (intended changes, review only):")
    for rel in differing:
        print("  " + rel)

    restore = (forced + missing) if forced else missing
    if not restore:
        print("\nnothing to restore")
        return
    if dry:
        print("\ndry run: would restore %s" % ", ".join(restore))
        return

    print("")
    for rel in restore:
        blob = fetch_blob(repo, ref, rel)
        dest = os.path.join(root, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as fh:
            fh.write(blob)
        print("restored %s (%d bytes) from %s" % (rel, len(blob), ref[:12]))


if __name__ == "__main__":
    main()