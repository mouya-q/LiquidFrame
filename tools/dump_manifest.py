#!/usr/bin/env python3
"""Dump a minimal manifest summary from an APK's binary AndroidManifest.xml."""
import sys, zipfile, struct

def main():
    apk = sys.argv[1]
    out = sys.argv[2]
    with zipfile.ZipFile(apk) as zf:
        data = zf.read("AndroidManifest.xml")
    # Just dump as hex with a header — binary AXML needs a real parser
    with open(out, "w") as f:
        f.write("# AndroidManifest.xml (binary, %d bytes)\n" % len(data))
        f.write("# This is a binary XML file. Use aapt/apkanalyzer for full decode.\n")
        f.write("# Raw hex dump (first 4096 bytes):\n")
        for i in range(0, min(len(data), 4096), 16):
            hex_part = " ".join("%02x" % b for b in data[i:i+16])
            ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in data[i:i+16])
            f.write("%04x: %-48s %s\n" % (i, hex_part, ascii_part))
    print("Manifest dump: %s (%d bytes)" % (out, len(data)))

if __name__ == "__main__":
    main()
