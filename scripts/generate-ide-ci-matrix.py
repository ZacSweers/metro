#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Select the newest configured IntelliJ and one Android Studio per IJ branch for PRs."""

import argparse
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET


INTELLIJ_RELEASES_URL = "https://data.services.jetbrains.com/products/releases?code=IIU&type=release"
ANDROID_STUDIO_RELEASES_URL = "https://jb.gg/android-studio-releases-list.xml"


def read_versions(path: Path) -> list[str]:
    """Read configured IDE coordinates without their display names or comments."""
    versions = []
    for line in path.read_text().splitlines():
        version = line.split("#", 1)[0].strip()
        if version:
            versions.append(version)
    return versions


def platform_builds() -> tuple[dict[str, str], dict[str, str]]:
    """Resolve marketing versions to IntelliJ platform builds using the official feeds."""
    with urllib.request.urlopen(INTELLIJ_RELEASES_URL, timeout=30) as response:
        intellij_releases = json.load(response)
    intellij_builds = {
        release["version"]: release["build"]
        for releases in intellij_releases.values()
        for release in releases
    }

    with urllib.request.urlopen(ANDROID_STUDIO_RELEASES_URL, timeout=30) as response:
        studio_releases = ET.parse(response).getroot()
    studio_builds = {
        release.findtext("version"): release.findtext("platformBuild")
        for release in studio_releases.findall("item")
    }
    return intellij_builds, studio_builds


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(component) for component in version.split("."))


def select_pr_versions(
    versions: list[str], intellij_builds: dict[str, str], studio_builds: dict[str, str]
) -> list[str]:
    """Compare platform builds and preserve the configured order of the selected IDEs."""
    latest = {}
    for entry in versions:
        product, version, *extra = entry.split(":")
        if product == "IU":
            # IntelliJ previews already use their platform build as the version.
            build = version if extra else intellij_builds[version]
            group = "IU"
        elif product == "AS":
            build = studio_builds[version]
            group = "AS:" + build.split(".")[0]
        else:
            raise ValueError(f"Unsupported IDE product: {product}")

        # Studio releases can share a platform build. Their product version breaks the tie.
        key = (version_key(build), version_key(version))
        if group not in latest or key > latest[group][0]:
            latest[group] = (key, entry)

    selected = {entry for _, entry in latest.values()}
    return [entry for entry in versions if entry in selected]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--ide", default="")
    parser.add_argument(
        "--versions-file",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "ide-integration-tests/ide-versions.txt",
    )
    args = parser.parse_args()

    if args.ide and args.ide != "all":
        versions = [args.ide]
    else:
        versions = read_versions(args.versions_file)
        if args.event_name == "pull_request":
            versions = select_pr_versions(versions, *platform_builds())
    print(json.dumps(versions, separators=(",", ":")))


if __name__ == "__main__":
    main()
