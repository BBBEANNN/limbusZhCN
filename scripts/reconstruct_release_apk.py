#!/usr/bin/env python3
"""Reconstruct an exact, already-signed APK from verified base/literal byte ranges."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def reconstruct(base_path: Path, payload_path: Path, output_path: Path) -> None:
    base = base_path.read_bytes()
    with zipfile.ZipFile(payload_path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        literals = archive.read("literal.bin")
    if manifest.get("format") != 1:
        raise ValueError("Unsupported APK payload format")
    if hashlib.sha256(base).hexdigest() != manifest["baseSha256"]:
        raise ValueError("Upstream APK SHA-256 mismatch")
    expected_size = manifest["outputSize"]
    if not isinstance(expected_size, int) or not 0 < expected_size <= 512 * 1024 * 1024:
        raise ValueError("Invalid APK output size")
    temporary = output_path.with_name(output_path.name + ".part")
    digest = hashlib.sha256()
    total = 0
    try:
        with temporary.open("wb") as output:
            for operation in manifest["operations"]:
                if len(operation) != 3:
                    raise ValueError("Invalid APK operation")
                kind, offset, length = operation
                if kind not in ("base", "literal"):
                    raise ValueError("Unknown APK operation")
                source = base if kind == "base" else literals
                if (not isinstance(offset, int) or not isinstance(length, int)
                        or offset < 0 or length < 0 or offset + length > len(source)
                        or total + length > expected_size):
                    raise ValueError("APK byte range is out of bounds")
                data = memoryview(source)[offset:offset + length]
                output.write(data)
                digest.update(data)
                total += length
        if total != expected_size or digest.hexdigest() != manifest["outputSha256"]:
            raise ValueError("Reconstructed APK integrity mismatch")
        temporary.replace(output_path)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Verified APK: {digest.hexdigest()}  {output_path.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path)
    parser.add_argument("payload", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    reconstruct(args.base, args.payload, args.output)
