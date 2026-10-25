"""Install official, hash-verified Satoshi files outside tracked source assets."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPECS = json.loads((ROOT / "scripts/satoshi_fonts.json").read_text())
DEST = ROOT / "src/main/resources/fonts"

def main():
    if all((DEST / s["file"]).is_file() and hashlib.sha256((DEST / s["file"]).read_bytes()).hexdigest() == s["sha256"] for s in SPECS):
        print("Satoshi fonts verified.")
        return
    request = urllib.request.Request("https://api.fontshare.com/v2/fonts/download/satoshi", headers={"User-Agent": "Alter-font-setup/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        archive = zipfile.ZipFile(io.BytesIO(response.read(8 * 1024 * 1024)))
    verified = []
    for s in SPECS:
        data = archive.read(s["member"])
        if hashlib.sha256(data).hexdigest() != s["sha256"]:
            raise RuntimeError("Official Satoshi artifact changed. Review provenance before changing hashes.")
        verified.append((s["file"], data))
    DEST.mkdir(parents=True, exist_ok=True)
    for name, data in verified:
        (DEST / name).write_bytes(data)
    print("Official Satoshi assets installed and verified.")

if __name__ == "__main__":
    main()
