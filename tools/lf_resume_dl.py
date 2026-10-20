#!/usr/bin/env python3
"""Resume-capable artifact download: fetch the Azure blob in ranged chunks.

The container's TLS drops long streams, so instead of one urlopen().read()
loop we request small ranges (4 MB each) with retries, appending to a local
file. Works against signed Azure blob URLs that honour the Range header.
"""
import os
import ssl
import sys
import time
import urllib.request

CTX = ssl._create_unverified_context()
CHUNK = 4 * 1024 * 1024
RETRIES = 8


def fetch_range(url, start, end):
    """Return bytes [start, end) of the resource. end is exclusive."""
    req = urllib.request.Request(url, headers={
        "Range": "bytes=%d-%d" % (start, end - 1),
        "User-Agent": "Mozilla/5.0",
    })
    for attempt in range(1, RETRIES + 1):
        try:
            resp = urllib.request.urlopen(req, timeout=300, context=CTX)
            data = resp.read()
            if resp.status in (200, 206):
                return data
            raise IOError("status %d" % resp.status)
        except Exception as e:
            if attempt == RETRIES:
                raise
            print("  retry %d after error: %s" % (attempt, e), flush=True)
            time.sleep(2.0)


def main():
    if len(sys.argv) != 4:
        print("usage: resume_dl.py <url> <out> <total_bytes>")
        return 2
    url, out, total = sys.argv[1], sys.argv[2], int(sys.argv[3])

    # Resume from whatever is already on disk.
    done = os.path.getsize(out) if os.path.exists(out) else 0
    print("starting at %d of %d" % (done, total), flush=True)

    with open(out, "ab") as f:
        while done < total:
            end = min(done + CHUNK, total)
            data = fetch_range(url, done, end)
            f.write(data)
            done = end
            print("  got %d / %d (%.1f%%)" % (done, total, 100.0 * done / total), flush=True)
    print("complete: %d bytes" % done, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
