#!/usr/bin/env python3
"""Fetch the build APK log for a given GitHub Actions run id."""
import io
import os
import ssl
import sys
import urllib.request
import zipfile

TOK = os.environ.get("GH_TOKEN", "")
CTX = ssl._create_unverified_context()
RUN_ID = sys.argv[1] if len(sys.argv) > 1 else None

if not TOK or not RUN_ID:
    print("usage: GH_TOKEN=xxx fetch_build_log.py <run_id>")
    sys.exit(1)

URL = f"https://api.github.com/repos/mouya-q/LiquidFrame/actions/runs/{RUN_ID}/logs"
req = urllib.request.Request(
    URL,
    headers={"User-Agent": "x", "Accept": "application/vnd.github+json", "Authorization": "token " + TOK},
)
raw = urllib.request.urlopen(req, timeout=120, context=CTX).read()
z = zipfile.ZipFile(io.BytesIO(raw))
print("entries:", z.namelist())
for n in z.namelist():
    if "Build APK" in n or "Setup Gradle" in n:
        print("=====", n, "=====")
        print(z.read(n).decode("utf-8", "replace"))