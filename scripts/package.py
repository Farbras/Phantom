"""Package only explicit public project paths; optionally audit against a private local fixture."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
apk = root / "app/build/outputs/apk/release/app-release.apk"
with zipfile.ZipFile(apk) as z:
    abis = {name.split("/")[1] for name in z.namelist() if name.startswith("lib/")}
    if abis != {"arm64-v8a"}:
        raise SystemExit("Build the phone APK with -PphantomAbi=arm64-v8a first")
shutil.copyfile(apk, dist / "Phantom-1.0.0-arm64.apk")

directories = ["app/src", "app/libs", "trafficprobe/src", "docs", "native-sources", "scripts", "gradle"]
names = ["README.md", "LICENSE", ".gitignore", "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
         "gradlew", "gradlew.bat", "dependencies.sha256", "signing.local.properties.example",
         "app/build.gradle.kts", "app/gradle.lockfile", "trafficprobe/build.gradle.kts", "trafficprobe/gradle.lockfile"]
files = [root / n for n in names]
for folder in directories:
    files.extend(p for p in (root / folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts)
files = sorted(set(files))

secrets = set()
audit_dir = os.environ.get("PHANTOM_AUDIT_DIR")
if audit_dir and (Path(audit_dir) / "subscription.json").exists():
    private = json.loads((Path(audit_dir) / "subscription.json").read_text(encoding="utf8"))
    def walk(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key.lower() in {"id", "password", "privatekey", "publickey"} and isinstance(item, str) and len(item) >= 12:
                    secrets.add(item.encode())
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(private)
if os.environ.get("PHANTOM_SUBSCRIPTION_URL"):
    secrets.add(os.environ["PHANTOM_SUBSCRIPTION_URL"].encode())

matches = []
for path in files:
    data = path.read_bytes()
    if any(secret in data for secret in secrets):
        matches.append(str(path.relative_to(root)))
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not name.endswith("/") and any(secret in z.read(name) for secret in secrets):
            matches.append("APK/" + name)
if matches:
    raise SystemExit("Private value matched in: " + ", ".join(matches))

report = {"sourceFiles": len(files), "privateValuesCompared": len(secrets), "matches": len(matches),
          "privateInputBundled": False, "sourceWhitelist": directories,
          "note": "Checks local fixture identifiers/passwords/keys and the optional URL against source files and decompressed APK entries."}
(root / "docs/privacy-audit.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
files = sorted(set(files + [root / "docs/privacy-audit.json"]))
archive = dist / "Phantom-1.0.0-source.zip"
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in files:
        name = path.relative_to(root).as_posix()
        storage = zipfile.ZIP_STORED if path.suffix in {".zip", ".aar", ".gz", ".xz"} else zipfile.ZIP_DEFLATED
        z.write(path, "Phantom/" + name, compress_type=storage)
manifest = {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in [dist / "Phantom-1.0.0-arm64.apk", archive]}
(dist / "SHA256.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf8")
print(json.dumps({"artifacts": manifest, "privacy": report}, indent=2))
