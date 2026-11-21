#!/usr/bin/env python3
"""Download a GitHub Actions artifact, following the Azure redirect manually."""
import io, json, os, ssl, sys, urllib.error, urllib.request, zipfile

CTX = ssl._create_unverified_context()
TOKEN = os.environ.get("GH_TOKEN", "").strip()
API = "https://api.github.com"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def get(url, headers, timeout=180):
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
            req2 = urllib.request.Request(loc, headers={"User-Agent": "lf-dl"})
            with urllib.request.urlopen(req2, context=CTX, timeout=timeout) as r2:
                return r2.status, r2.read()
        raise

def main():
    repo = sys.argv[1]
    artifact_id = sys.argv[2]
    out = sys.argv[3]
    url = "%s/repos/%s/actions/artifacts/%s/zip" % (API, repo, artifact_id)
    status, data = get(url, {
        "Authorization": "token " + TOKEN,
        "Accept": "application/vnd.github+json",
        "User-Agent": "lf-dl",
    })
    print("status:", status, "bytes:", len(data))
    if status != 200 or data[:2] != b"PK":
        print("not a zip:", data[:300])
        return 1
    zf = zipfile.ZipFile(io.BytesIO(data))
    names = zf.namelist()
    print("contents:", names)
    for n in names:
        if n.endswith(".apk"):
            apk = zf.read(n)
            with open(out, "wb") as f:
                f.write(apk)
            print("extracted:", n, "->", out, len(apk), "bytes")
            return 0
    # no apk inside, save the zip itself
    with open(out, "wb") as f:
        f.write(data)
    print("no apk found, saved raw zip ->", out)
    return 0

if __name__ == "__main__":
    sys.exit(main())
