"""Fetch a GitHub Actions job log, handling the Azure redirect correctly.

Run:  python3 tools/gh_job_log.py <job_id> [out_file]

The logs endpoint answers with a 302 to a pre-signed Azure blob URL. That URL must be
fetched **without** the Authorization header: sending it makes Azure reject the request
with 401. Python's default opener follows the redirect and forwards the header, so the
redirect is intercepted and the Location re-fetched bare.
"""

import io
import json
import os
import ssl
import sys
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_CANDIDATES = [
    os.path.join(HERE, "..", "..", "push", ".gh_token"),
    os.path.join(HERE, ".gh_token"),
]
REPO = "mouya-q/LiquidFrame"


def token():
    for path in TOKEN_CANDIDATES:
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8") as fh:
                return fh.read().strip()
    raise SystemExit("no .gh_token found")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    job_id = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/joblog.txt"

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    url = "https://api.github.com/repos/%s/actions/jobs/%s/logs" % (REPO, job_id)
    req = urllib.request.Request(url, headers={
        "Authorization": "Bearer " + token(),
        "User-Agent": "gh-job-log",
        "Accept": "application/vnd.github+json",
    })

    opener = urllib.request.build_opener(_NoRedirect)
    location = None
    try:
        with opener.open(req, timeout=60) as resp:
            body = resp.read()
    except urllib.error.HTTPError as exc:
        if exc.code not in (301, 302, 303, 307, 308):
            raise SystemExit("HTTP %s fetching log" % exc.code)
        location = exc.headers.get("Location")
        body = None

    if location:
        # Deliberately no Authorization header on the pre-signed URL.
        with urllib.request.urlopen(location, timeout=300, context=ctx) as resp:
            body = resp.read()

    if not body:
        raise SystemExit("empty log body")

    try:
        zf = zipfile.ZipFile(io.BytesIO(body))
        text = "".join(
            zf.read(name).decode("utf-8", "replace") for name in zf.namelist()
        )
    except zipfile.BadZipFile:
        text = body.decode("utf-8", "replace")

    with open(out, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("wrote %s (%d lines)" % (out, text.count("\n")))


if __name__ == "__main__":
    main()