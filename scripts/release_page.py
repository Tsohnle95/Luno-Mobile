#!/usr/bin/env python3
"""Render release download pages; optionally refresh the latest release body only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MARKER = "<!-- luno-download-page -->"
HISTORY = "<!-- luno-release-history -->"


def render(release, repository, minimum_sdk):
    tag = release["tag_name"]
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Expected a stable version tag")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid repository")
    base = f"https://github.com/{repository}"
    name = f"Luno-Mobile-{tag}.apk"
    asset = next(a for a in release["assets"] if a["name"] == name)
    download = f"{base}/releases/download/{tag}/{name}"
    if asset["browser_download_url"] != download:
        raise ValueError("APK URL does not match this repository and release")
    art = f"https://raw.githubusercontent.com/{repository}/main/docs/assets/releases"
    android = {29: "10", 30: "11", 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16"}
    requirement = f"Android {android[minimum_sdk]} or later" if minimum_sdk in android else f"Android API {minimum_sdk} or later"
    size = asset["size"] / (1024 * 1024)
    body = f'''{MARKER}
![Luno Mobile — Your music. Your library.]({art}/luno-mobile-banner.png)

## Download Luno Mobile

Listen to your music, organize playlists, and discover your next favorite song.

<a href="{download}"><img src="{art}/download-android.png" alt="Download Luno Mobile for Android" width="320" height="64"></a>

| Platform | Requirements | Download |
| :--- | :--- | :--- |
| **Android** | {requirement} · Universal APK | [**Download {tag}**]({download}) · {size:.1f} MB |

'''
    notes = ROOT / ".github/release-notes" / f"{tag}.md"
    if notes.is_file():
        body += "### What’s new\n\n" + notes.read_text().strip() + "\n\n"
    body += '''### Install or update

1. Download the APK on your Android phone.
2. Open it and allow installation from your browser or file manager if Android asks.
3. Tap **Install**, then **Open**. Updating an existing Luno installation keeps your library.

You can also update from **Luno → Profile → Settings → App → Check for updates**.

'''
    digest = asset.get("digest") or ""
    if digest:
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", digest):
            raise ValueError("Invalid APK digest")
        body += f'''<details>
<summary>File details and SHA-256</summary>

**File:** `{name}`

**Package:** `com.luno.mobile`

**SHA-256:**

```text
{digest.removeprefix("sha256:")}
```

</details>

'''
    old = release.get("body") or ""
    history = old.partition(HISTORY)[2].strip() if MARKER in old else old.strip()
    return body + HISTORY + ("\n\n" + history if history else "") + "\n"


def minimum_sdk(tag):
    config = subprocess.check_output(["git", "show", f"{tag}:app/build.gradle.kts"], cwd=ROOT, text=True)
    return int(re.search(r"minSdk\s*=\s*(\d+)", config).group(1))


def api(repository, endpoint, payload=None):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "Luno-release-presentation"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if payload is not None and not token:
        raise RuntimeError("Release editing requires the workflow token")
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(f"https://api.github.com/repos/{repository}/{endpoint}",
        data=data, headers=headers, method="PATCH" if payload is not None else "GET")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def update_latest(repository):
    release = api(repository, "releases/latest")
    if release["draft"] or release["prerelease"]:
        raise ValueError("Expected a published stable release")
    body = render(release, repository, minimum_sdk(release["tag_name"]))
    # Deliberately omit assets, tags, target, publication state and latest status.
    updated = api(repository, f"releases/{release['id']}",
        {"name": f"Luno Mobile {release['tag_name']}", "body": body})
    if [a["id"] for a in updated["assets"]] != [a["id"] for a in release["assets"]]:
        raise RuntimeError("Release asset identity changed unexpectedly")
    print(f"Updated release presentation: {updated['html_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "Tsohnle95/Luno-Mobile"))
    parser.add_argument("--tag", default=os.environ.get("GITHUB_REF_NAME"))
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--apk", type=Path)
    group.add_argument("--release-json", type=Path)
    group.add_argument("--update-latest", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.update_latest:
        update_latest(args.repository)
        return
    if not args.output:
        parser.error("--output is required when rendering a file")
    if args.release_json:
        release = json.loads(args.release_json.read_text())
    else:
        if not args.tag or not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
            parser.error("--tag must be a stable version tag")
        digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        name = f"Luno-Mobile-{args.tag}.apk"
        release = {"tag_name": args.tag, "assets": [{"name": name, "size": args.apk.stat().st_size,
            "digest": "sha256:" + digest,
            "browser_download_url": f"https://github.com/{args.repository}/releases/download/{args.tag}/{name}"}]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(release, args.repository, minimum_sdk(release["tag_name"])))
    print(f"Rendered {args.output}")


if __name__ == "__main__":
    main()
