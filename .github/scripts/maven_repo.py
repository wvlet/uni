#!/usr/bin/env python3
"""Helpers for the wvlet Maven repository (https://repo.wvlet.org/maven2) on Cloudflare R2.

Subcommands:

  metadata STAGING EXISTING_BASE OUT
      For every artifact directory under STAGING (a Maven layout such as target/sona-staging), fetch
      its current maven-metadata.xml from EXISTING_BASE (a URL or a local directory; missing files
      mean a new artifact), add the staged versions, and write the merged maven-metadata.xml with
      .md5 and .sha1 checksums to the same relative path under OUT.

  bundle REPO_DIR VERSION OUT_ZIP
      Zip every file of VERSION under REPO_DIR (a Maven layout) into a Maven Central Portal bundle,
      leaving out maven-metadata files, which Central generates itself.

Version directories are found by their POM: a directory that holds `<artifact>-<version>.pom` is a
version directory, and its parent is the artifact directory.
"""

import hashlib
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

METADATA = "maven-metadata.xml"


def version_key(v: str):
    # Uni versions are YYYY.milestone.patch; compare numeric parts numerically and keep any
    # non-numeric suffix after the plain release with the same numbers.
    parts = re.split(r"[.\-]", v)
    return [(0, int(p), "") if p.isdigit() else (1, 0, p) for p in parts]


def find_versions(staging: Path):
    """Yield (artifact_dir_relpath, group_id, artifact_id, version) for every staged POM."""
    for pom in sorted(staging.rglob("*.pom")):
        version_dir = pom.parent
        artifact_dir = version_dir.parent
        version = version_dir.name
        artifact_id = artifact_dir.name
        if pom.name != f"{artifact_id}-{version}.pom":
            continue
        group_path = artifact_dir.parent.relative_to(staging)
        yield artifact_dir.relative_to(staging), ".".join(group_path.parts), artifact_id, version


def read_existing(base: str, rel: Path):
    location = f"{base.rstrip('/')}/{rel.as_posix()}/{METADATA}"
    if re.match(r"^https?://", base):
        req = urllib.request.Request(location, headers={"Cache-Control": "no-cache"})
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                return res.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise
    path = Path(location)
    return path.read_bytes() if path.exists() else None


def existing_versions(data):
    if data is None:
        return []
    root = ET.fromstring(data)
    return [v.text.strip() for v in root.findall("./versioning/versions/version") if v.text]


def render(group_id: str, artifact_id: str, versions) -> bytes:
    ordered = sorted(set(versions), key=version_key)
    latest = ordered[-1]
    now = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        "<metadata>",
        f"  <groupId>{group_id}</groupId>",
        f"  <artifactId>{artifact_id}</artifactId>",
        "  <versioning>",
        f"    <latest>{latest}</latest>",
        f"    <release>{latest}</release>",
        "    <versions>",
        *[f"      <version>{v}</version>" for v in ordered],
        "    </versions>",
        f"    <lastUpdated>{now}</lastUpdated>",
        "  </versioning>",
        "</metadata>",
        "",
    ]
    return "\n".join(lines).encode("utf-8")


def cmd_metadata(staging: str, base: str, out: str):
    staging_path, out_path = Path(staging), Path(out)
    artifacts = {}
    for rel, group_id, artifact_id, version in find_versions(staging_path):
        entry = artifacts.setdefault(rel, (group_id, artifact_id, set()))
        entry[2].add(version)
    if not artifacts:
        sys.exit(f"No POM files found under {staging}")
    for rel, (group_id, artifact_id, versions) in sorted(artifacts.items()):
        merged = existing_versions(read_existing(base, rel)) + sorted(versions)
        data = render(group_id, artifact_id, merged)
        target = out_path / rel / METADATA
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        for algo in ("md5", "sha1"):
            Path(f"{target}.{algo}").write_text(hashlib.new(algo, data).hexdigest())
        print(f"{rel.as_posix()}: {', '.join(sorted(versions, key=version_key))}")


def cmd_bundle(repo_dir: str, version: str, out_zip: str):
    repo = Path(repo_dir)
    files = [
        f
        for f in sorted(repo.rglob("*"))
        if f.is_file() and f.parent.name == version and not f.name.startswith("maven-metadata")
    ]
    if not any(f.name.endswith(".pom") for f in files):
        sys.exit(f"No POM files for version {version} under {repo_dir}")
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            z.write(f, f.relative_to(repo).as_posix())
    print(f"Bundled {len(files)} files for {version} into {out_zip}")


if __name__ == "__main__":
    commands = {"metadata": (cmd_metadata, 3), "bundle": (cmd_bundle, 3)}
    if len(sys.argv) < 2 or sys.argv[1] not in commands or len(sys.argv) - 2 != commands[sys.argv[1]][1]:
        sys.exit(__doc__)
    fn, _ = commands[sys.argv[1]]
    fn(*sys.argv[2:])
