#!/usr/bin/env python3
"""Fetch GitHub Actions logs for LiquidFrame (robust)."""
import http.client, ssl, json, os, zipfile, io
from urllib.parse import urlparse

TOK = os.environ.get("GH_TOKEN", "")
CTX = ssl.create_default_context()
OWNER = "mouya-q"
REPO = "LiquidFrame"


def api(method, path, accept="application/vnd.github+json", timeout=40):
    c = http.client.HTTPSConnection("api.github.com", timeout=timeout, context=CTX)
    h = {"User-Agent": "x", "Accept": accept}
    if TOK:
        h["Authorization"] = "token " + TOK
    c.request(method, path, headers=h)
    r = c.getresponse()
    return r, r.read()


def json_api(path):
    r, raw = api("GET", path)
    try:
        return r.status, json.loads(raw)
    except Exception:
        print("JSON ERR", path, r.status, raw[:300])
        return r.status, None


def main():
    s, d = json_api(f"/repos/{OWNER}/{REPO}/actions/runs?per_page=3")
    if not d:
        return
    for run in d.get("workflow_runs", []):
        print("RUN", run["id"], run["status"], run["conclusion"], run["head_sha"][:12])
    rid = d["workflow_runs"][0]["id"]

    s, jobs = json_api(f"/repos/{OWNER}/{REPO}/actions/runs/{rid}/jobs")
    if jobs:
        for j in jobs.get("jobs", []):
            print("JOB", j["id"], j["name"], j["conclusion"])
            for st in j.get("steps", []):
                print("   ", st["number"], st["name"], st["conclusion"])

    r, raw = api("GET", f"/repos/{OWNER}/{REPO}/actions/runs/{rid}/logs", timeout=90)
    print("logs status", r.status)
    if r.status in (301, 302, 303, 307, 308):
        loc = r.getheader("Location")
        u = urlparse(loc)
        c2 = http.client.HTTPSConnection(u.netloc, timeout=180, context=CTX)
        c2.request("GET", u.path + ("?" + u.query if u.query else ""),
                   headers={"User-Agent": "x"})
        r2 = c2.getresponse()
        print("cdn status", r2.status)
        raw = r2.read()
    if raw[:2] != b"PK":
        print("NOT ZIP:", raw[:300])
        return

    z = zipfile.ZipFile(io.BytesIO(raw))
    print("ZIP entries:", len(z.namelist()))
    for name in z.namelist():
        if "build" not in name.lower():
            continue
        data = z.read(name).decode("utf-8", "replace")
        print("=" * 70)
        print("FILE:", name)
        for ln in data.splitlines()[-200:]:
            print(ln)


if __name__ == "__main__":
    main()