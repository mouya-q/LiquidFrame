#!/usr/bin/env python3
"""Download a GitHub Actions run log zip and flatten it to text.

The /logs endpoint 302-redirects to Azure Blob Storage. urllib's default
redirect handler forwards the Authorization header to Azure, which rejects it
with 403, so we follow the redirect manually and drop the auth header.
"""
import io
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
import zipfile

CTX = ssl._create_unverified_context()
API = "https://api.github.com"
TOKEN = os.environ.get("GH_TOKEN", "").strip()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(url, headers, timeout=180):
    """GET following redirects manually, dropping auth on cross-host hops."""
    req = urllib.request.Request(url, headers=headers)
    opener = urllib.request.build_opener(NoRedirect)
    try:
        resp = opener.open(req, timeout=timeout)
        return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            loc = e.headers.get("Location")
            if not loc:
                raise
            req2 = urllib.request.Request(loc, headers={"User-Agent": "ghkit"})
            with urllib.request.urlopen(req2, context=CTX, timeout=timeout) as r2:
                return r2.status, r2.read()
        raise


def main():
    repo, run_id, out = sys.argv[1], sys.argv[2], sys.argv[3]
    url = "%s/repos/%s/actions/runs/%s/logs" % (API, repo, run_id)
    status, data = get(url, {
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "ghkit",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    print("status:", status, "bytes:", len(data))
    if status != 200 or data[:2] != b"PK":
        print("not a zip:", data[:300])
        return 1
    zf = zipfile.ZipFile(io.BytesIO(data))
    chunks = []
    for n in sorted(zf.namelist()):
        try:
            body = zf.read(n).decode("utf-8", "ignore")
        except Exception as e:
            body = "<<read error %s>>" % e
        chunks.append("\n########## %s ##########\n%s" % (n, body))
    text = "".join(chunks)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
    print("written:", out, len(text), "chars")
    return 0


if __name__ == "__main__":
    sys.exit(main())