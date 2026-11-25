"""Verify the exact upstream native and geo artifacts bundled in Phantom."""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
for line in (root / "dependencies.sha256").read_text().splitlines():
    digest, name = line.split("  ", 1)
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if actual != digest:
        raise SystemExit(f"Checksum mismatch: {name}")
    print(f"OK {name}")
