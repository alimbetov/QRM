#!/usr/bin/env python3
"""Deterministic, dependency-free RGB PNG fixture for isolated k6 media load."""
import argparse
import struct
import zlib
from pathlib import Path

def chunk(tag: bytes, body: bytes) -> bytes:
    value = tag + body
    return struct.pack(">I", len(body)) + value + struct.pack(">I", zlib.crc32(value) & 0xffffffff)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output", default="perf/k6/fixtures/media-upload-640x480.png")
    p.add_argument("--width", type=int, default=640)
    p.add_argument("--height", type=int, default=480)
    args = p.parse_args()
    if not (1 <= args.width <= 1600 and 1 <= args.height <= 1600):
        raise SystemExit("Fixture dimensions must be between 1 and 1600")
    # Non-personal, synthetic and deterministic pixels; no EXIF/PII.
    raw = b"".join(
        b"\x00" + bytes(((x*17+y*3)%256,(x*7+y*11)%256,(x*5+y*13)%256)
            for x in range(args.width)) for y in range(args.height)
    )
    payload = (
        b"\x89PNG\r\n\x1a\n" +
        chunk(b"IHDR", struct.pack(">IIBBBBB", args.width, args.height, 8, 2, 0, 0, 0)) +
        chunk(b"IDAT", zlib.compress(raw, 6)) +
        chunk(b"IEND", b"")
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload)
    print(f"Generated synthetic PNG: {output} ({len(payload)} bytes)")

if __name__ == "__main__":
    main()
