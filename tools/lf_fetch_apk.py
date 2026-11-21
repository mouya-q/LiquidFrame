#!/usr/bin/env python3
"""Fetch one GitHub Actions artifact and extract the APK from it.

Written because the earlier one-shot download kept dying silently: it buffered the whole
19 MB zip in memory before printing anything, so a slow Azure redirect looked like a hang
with an empty log. This version streams the body in 256 KB chunks and reports progress, and
it reads the redirect target out of the HTTPError when the API answers 302 (GitHub sends
the artifact to Azure, and that hop is where the container's flaky TLS usually bites).
"""
import io
import os
import ssl
import sys
import urllib.error
import urllib.request
import zipfile

CTX = ssl._create_unverified_context()
API = "https://api.github.com"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Stops urllib from following the 302 itself.

    GitHub answers the artifact request with a redirect to a signed Azure blob URL. If urllib
    follows it, it replays the `Authorization: token ...` header on the Azure request, and Azure
    rejects that with 403 "Server failed to authenticate the request" — the token is not part of
    the Azure signature. Taking the Location by hand and issuing a fresh, unauthenticated request
    is the only thing that works.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def artifact_zip_location(repo, artifact_id, token, timeout=300):
    url = "%s/repos/%s/actions/artifacts/%s/zip" % (API, repo, artifact_id)
    headers = {
        "Authorization": "token " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": "lf-dl",
    }
    request = urllib.request.Request(url, headers=headers)
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        response = opener.open(request, timeout=timeout)
        return response.headers.get("Location")
    except urllib.error.HTTPError as e:
        if e.code in (301, 302, 303, 307, 308):
            return e.headers.get("Location")
        raise


def stream(location, timeout=900):
    request = urllib.request.Request(location, headers={"User-Agent": "Mozilla/5.0"})
    response = urllib.request.urlopen(request, timeout=timeout, context=CTX)
    total = response.headers.get("Content-Length")
    print("content-length:", total)
    buffer = io.BytesIO()
    read = 0
    while True:
        chunk = response.read(262144)
        if not chunk:
            break
        buffer.write(chunk)
        read += len(chunk)
        if read % (5 * 1024 * 1024) < 262144:
            print("  got %.1f MB" % (read / 1048576.0), flush=True)
    return buffer.getvalue()


def main():
    if len(sys.argv) != 4:
        print("usage: lf_fetch_apk.py <repo> <artifact_id> <out.apk>")
        return 2
    repo, artifact_id, out = sys.argv[1], sys.argv[2], sys.argv[3]
    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        print("GH_TOKEN is not set")
        return 1

    location = artifact_zip_location(repo, artifact_id, token)
    if not location:
        print("no redirect location for artifact", artifact_id)
        return 1
    print("redirect ok", flush=True)

    data = stream(location)
    print("bytes:", len(data))
    if data[:2] != b"PK":
        print("not a zip, first bytes:", data[:200])
        return 1

    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            if name.endswith(".apk"):
                apk = archive.read(name)
                with open(out, "wb") as handle:
                    handle.write(apk)
                print("extracted:", name, "->", out, len(apk), "bytes")
                return 0

    with open(out, "wb") as handle:
        handle.write(data)
    print("no apk inside, saved raw zip ->", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())