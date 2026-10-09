"""Emit the public updater feed from the exact APK built by CI."""
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

root = Path("android/TWBoardingScanner")
gradle = (root / "v04-overlay/app/build.gradle.kts").read_text()
version = re.search(r'versionName\s*=\s*"([\d.]+)"', gradle).group(1)
code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle).group(1))
apk = Path(f"TWBoardingScanner-v{version}.apk")
content = apk.read_bytes()
manifest = {
    "packageName": "com.rex.twboardingscanner",
    "versionCode": code,
    "versionName": version,
    "url": f"https://github.com/rex0966445518/stock-monitor-updates/releases/download/android-v{version}/{apk.name}",
    "size": len(content),
    "sha256": hashlib.sha256(content).hexdigest(),
    "notes": (root / "UPDATE_NOTES.md").read_text().strip(),
    "publishedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
Path("android-update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
Path("android-update-notes.txt").write_text(manifest["notes"] + "\n")
print(f"Android {version} ({code}): {len(content)} bytes; sha256 {manifest['sha256']}")
