#!/usr/bin/env python3

"""Fetches recent IntelliJ IDEA and Android Studio releases, resolves their
bundled Kotlin compiler versions, and outputs alias mappings.

Works entirely via HTTP/API calls - no IDE binaries are downloaded.

Usage:
  ./fetch-all-ide-kotlin-versions.py [--channels stable,canary,beta,eap,rc]

Dependencies: python3, gh (GitHub CLI, authenticated)
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from collections import OrderedDict

FILE_PATH = ".idea/libraries/kotlinc_kotlin_compiler_common.xml"
RAW_GH_BASE = "https://raw.githubusercontent.com/JetBrains/intellij-community"


def parse_kotlin_base_version(version_str):
    """Extract the numeric base (major, minor, patch) from a Kotlin version string.
    E.g. '2.2.20-ij252-24' -> (2, 2, 20), '2.3.20-dev-3964' -> (2, 3, 20)."""
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)", version_str)
    if m:
        return (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    return (0, 0, 0)


def read_kotlin_version_from_toml():
    """Try to read the kotlin version from gradle/libs.versions.toml relative to this script."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    toml_path = os.path.join(script_dir, "..", "gradle", "libs.versions.toml")
    try:
        with open(toml_path) as f:
            for line in f:
                m = re.match(r'^kotlin\s*=\s*"([^"]+)"', line.strip())
                if m:
                    return m.group(1)
    except FileNotFoundError:
        pass
    return None


# ─── HTTP / GitHub helpers ───────────────────────────────────────────────────


def fetch_url(url):
    """Fetch a URL and return (body, http_status)."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "fetch-ide-kotlin-versions"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode("utf-8"), resp.status
    except urllib.error.HTTPError as e:
        return "", e.code
    except Exception:
        return "", 0


def gh_api(endpoint, jq_filter=None):
    """Call gh api and return the parsed JSON, or raw string if jq_filter is used."""
    cmd = ["gh", "api", endpoint]
    if jq_filter:
        cmd += ["-q", jq_filter]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if result.returncode != 0:
            return None
        if jq_filter:
            return result.stdout.strip()
        return json.loads(result.stdout) if result.stdout.strip() else None
    except Exception:
        return None


# ─── Kotlin version resolution ───────────────────────────────────────────────


def fetch_kotlin_version(tag):
    """Fetch the Kotlin version from a given intellij-community tag."""
    url = f"{RAW_GH_BASE}/{tag}/{FILE_PATH}"
    body, status = fetch_url(url)
    if status == 200:
        m = re.search(r'kotlin-compiler-common-for-ide:([^"]+)', body)
        if m:
            return m.group(1)
    return None


def find_nearest_tag(major):
    """Find the nearest idea/<major>.* tag."""
    raw = gh_api(
        f"repos/JetBrains/intellij-community/git/matching-refs/tags/idea/{major}.",
        jq_filter=".[].ref",
    )
    if not raw:
        return None
    tags = [t.replace("refs/tags/", "") for t in raw.splitlines() if t.strip()]
    if not tags:
        return None

    # Sort by numeric segments and return the last (highest)
    def sort_key(t):
        parts = t.replace("idea/", "").split(".")
        return tuple(int(p) if p.isdigit() else 0 for p in parts)

    tags.sort(key=sort_key)
    return tags[-1]


def fetch_history(ref):
    """Fetch commit history for the kotlinc library file on a given ref.
    Returns list of (date_str, first_line_of_message)."""
    raw = gh_api(
        f"repos/JetBrains/intellij-community/commits?sha={ref}&path={FILE_PATH}&per_page=30",
        jq_filter='.[] | "\\(.commit.committer.date)\t\\(.commit.message | split("\\n")[0])"',
    )
    if not raw:
        return []
    entries = []
    for line in raw.splitlines():
        parts = line.split("\t", 1)
        if len(parts) == 2:
            entries.append((parts[0], parts[1]))
    return entries


VERSION_RE = re.compile(r"(\d+\.\d+\.\d+-[a-zA-Z0-9._-]+)")


def fetch_kotlin_tags(prefix):
    """Fetch tags from JetBrains/kotlin matching a prefix."""
    raw = gh_api(
        f"repos/JetBrains/kotlin/git/matching-refs/tags/{prefix}",
        jq_filter=".[].ref",
    )
    if not raw:
        return []
    return [t.replace("refs/tags/", "") for t in raw.splitlines() if t.strip()]


def get_merge_base(ij_tag):
    """Get the merge base commit SHA between master and an ij tag in JetBrains/kotlin."""
    result = gh_api(
        f"repos/JetBrains/kotlin/compare/master...{ij_tag}",
        jq_filter=".merge_base_commit.sha",
    )
    return result if result else None


def compare_commits(tag, commit_sha):
    """Compare a tag to a commit. Returns 'identical', 'ahead', 'behind', or 'error'."""
    result = gh_api(
        f"repos/JetBrains/kotlin/compare/{tag}...{commit_sha}",
        jq_filter=".status",
    )
    return result if result else "error"


def resolve_to_dev_build(tag, kotlin_version):
    """Resolve an -ij version to the nearest dev build using git ancestry in JetBrains/kotlin.

    This uses the merge-base between the ij tag and master to find the exact dev build
    that the ij branch diverged from, rather than relying on timestamp correlation.
    """
    if "-dev-" in kotlin_version and "-ij" not in kotlin_version:
        return kotlin_version
    if "-ij" not in kotlin_version:
        return kotlin_version

    # Parse: "2.2.20-ij252-24" -> base="2.2.20", platform="252", build="24"
    ij_match = re.match(r"^(\d+\.\d+\.\d+)-ij(\d+)-(\d+)$", kotlin_version)
    if not ij_match:
        return kotlin_version

    ij_base = ij_match.group(1)
    ij_platform = ij_match.group(2)
    ij_build = int(ij_match.group(3))

    # Find the matching ij tag in JetBrains/kotlin
    ij_tag_prefix = f"build-{ij_base}-ij{ij_platform}-"
    ij_tags = fetch_kotlin_tags(ij_tag_prefix)
    if not ij_tags:
        return kotlin_version

    # Find the tag with the closest build number
    best_ij_tag = None
    best_diff = 999999
    for t in ij_tags:
        m = re.search(r"-(\d+)$", t)
        if m:
            tag_build = int(m.group(1))
            diff = tag_build - ij_build
            if diff == 0:
                best_ij_tag = t
                break
            elif diff > 0 and diff < best_diff:
                best_diff = diff
                best_ij_tag = t
            elif best_ij_tag is None:
                best_ij_tag = t
                best_diff = abs(diff)

    if not best_ij_tag:
        return kotlin_version

    # Find the merge base with master
    merge_base = get_merge_base(best_ij_tag)
    if not merge_base:
        return kotlin_version

    # Find dev tags with matching base version
    dev_tag_prefix = f"build-{ij_base}-dev-"
    dev_tags = fetch_kotlin_tags(dev_tag_prefix)
    if not dev_tags:
        return kotlin_version

    # Extract and sort build numbers
    dev_nums = []
    for t in dev_tags:
        m = re.search(r"-(\d+)$", t)
        if m:
            dev_nums.append(int(m.group(1)))
    dev_nums = sorted(set(dev_nums))

    if not dev_nums:
        return kotlin_version

    # Binary search: find the highest dev tag that is an ancestor of merge base
    def check_tag(num):
        tag = f"build-{ij_base}-dev-{num}"
        status = compare_commits(tag, merge_base)
        if status in ("identical", "ahead"):
            return "ancestor"
        elif status == "behind":
            return "too_new"
        return "error"

    low, high = 0, len(dev_nums) - 1
    best_num = None

    while low <= high:
        mid = (low + high) // 2
        num = dev_nums[mid]
        result = check_tag(num)

        if result == "ancestor":
            best_num = num
            low = mid + 1
        elif result == "too_new":
            high = mid - 1
        else:
            high = mid - 1

    if best_num is not None:
        return f"{ij_base}-dev-{best_num}"

    # If no match found, try related base versions.
    # The ij label (e.g., 2.3.20-ij253-105) might actually branch from a different
    # version's dev track (e.g., 2.3.0-dev-9992).
    base_parts = ij_base.split(".")
    if len(base_parts) == 3 and base_parts[2] != "0":
        # Try X.Y.0 if the base is X.Y.Z where Z > 0
        alt_base = f"{base_parts[0]}.{base_parts[1]}.0"
        alt_dev_tag_prefix = f"build-{alt_base}-dev-"
        alt_dev_tags = fetch_kotlin_tags(alt_dev_tag_prefix)

        if alt_dev_tags:
            alt_dev_nums = []
            for t in alt_dev_tags:
                m = re.search(r"-(\d+)$", t)
                if m:
                    alt_dev_nums.append(int(m.group(1)))
            alt_dev_nums = sorted(set(alt_dev_nums))

            if alt_dev_nums:
                # Binary search on alternative base
                def check_alt_tag(num):
                    tag = f"build-{alt_base}-dev-{num}"
                    status = compare_commits(tag, merge_base)
                    if status in ("identical", "ahead"):
                        return "ancestor"
                    elif status == "behind":
                        return "too_new"
                    return "error"

                alt_low, alt_high = 0, len(alt_dev_nums) - 1
                alt_best_num = None

                while alt_low <= alt_high:
                    alt_mid = (alt_low + alt_high) // 2
                    alt_num = alt_dev_nums[alt_mid]
                    alt_result = check_alt_tag(alt_num)

                    if alt_result == "ancestor":
                        alt_best_num = alt_num
                        alt_low = alt_mid + 1
                    elif alt_result == "too_new":
                        alt_high = alt_mid - 1
                    else:
                        alt_high = alt_mid - 1

                if alt_best_num is not None:
                    return f"{alt_base}-dev-{alt_best_num}"

    return kotlin_version


# ─── Fetch IDE releases ─────────────────────────────────────────────────────


def fetch_intellij_releases(channels):
    """Fetch IntelliJ IDEA releases from JetBrains API.

    Returns the latest release per platform major per channel type, so we
    get e.g. IJ 2025.3.2 (253-stable), IJ 2025.2.6.1 (252-stable),
    IJ 2026.1 EAP (261-eap), etc.
    """
    type_map = {"stable": "release", "eap": "eap", "rc": "rc"}
    types = [type_map[c] for c in channels if c in type_map]
    if not types:
        return []

    type_param = ",".join(types)
    url = f"https://data.services.jetbrains.com/products/releases?code=IIU&type={type_param}"
    body, status = fetch_url(url)
    if status != 200 or not body:
        print(f"  WARNING: Failed to fetch IntelliJ releases (HTTP {status})", file=sys.stderr)
        return []

    data = json.loads(body)
    ch_labels = {"release": "stable", "eap": "eap", "rc": "rc"}

    # Collect all releases, then keep only the latest per (platform_major, channel)
    all_releases = []
    for _product_code, items in data.items():
        for r in items:
            build = r.get("build", "")
            version = r.get("version", "")
            release_type = r.get("type", "release")
            ch = ch_labels.get(release_type, release_type)
            if build:
                all_releases.append({
                    "ide_name": f"IntelliJ IDEA {version} ({ch})",
                    "platform_build": build,
                    "channel": ch,
                    "is_android_studio": False,
                })

    # API returns newest first, so first seen per (major, channel) is the latest
    seen = set()
    releases = []
    for r in all_releases:
        major = r["platform_build"].split(".")[0]
        key = (major, r["channel"])
        if key not in seen:
            seen.add(key)
            releases.append(r)

    return releases


def fetch_android_studio_releases(channels):
    """Fetch Android Studio releases from updates.xml."""
    url = "https://dl.google.com/android/studio/patches/updates.xml"
    body, status = fetch_url(url)
    if status != 200 or not body:
        print(f"  WARNING: Failed to fetch Android Studio releases (HTTP {status})", file=sys.stderr)
        return []

    root = ET.fromstring(body)
    releases = []
    for channel_elem in root.findall(".//channel"):
        channel_id = channel_elem.get("id", "")
        if "release" in channel_id:
            ch = "stable"
        elif "beta" in channel_id:
            ch = "beta"
        elif "rc" in channel_id:
            ch = "rc"
        elif "eap" in channel_id:
            ch = "canary"
        else:
            ch = "unknown"

        if ch not in channels:
            continue

        for build_elem in channel_elem.findall("build"):
            number = build_elem.get("number", "")
            api_version = build_elem.get("apiVersion", "")
            version = build_elem.get("version", "")
            name = build_elem.get("name", version)
            display = name or version or number

            if not api_version:
                continue

            # Strip AI- prefix
            platform_build = api_version.removeprefix("AI-")
            releases.append({
                "ide_name": f"Android Studio {display} ({ch})",
                "platform_build": platform_build,
                "channel": ch,
                "is_android_studio": True,
            })

    return releases


# ─── Main ────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description="Fetch IDE Kotlin version aliases")
    parser.add_argument(
        "--channels",
        default="stable,canary,beta,eap,rc",
        help="Comma-separated list of channels (default: stable,canary,beta,eap,rc)",
    )
    # Auto-detect default from gradle/libs.versions.toml if available
    detected_kotlin = read_kotlin_version_from_toml()
    default_min_kotlin = detected_kotlin or "2.2.20"
    parser.add_argument(
        "--min-kotlin",
        default=default_min_kotlin,
        help=f"Minimum Kotlin base version to include (default: {default_min_kotlin}"
             + (", from libs.versions.toml)" if detected_kotlin else ")"),
    )
    args = parser.parse_args()
    channels = set(args.channels.split(","))
    min_kotlin = parse_kotlin_base_version(args.min_kotlin)

    # Check for gh CLI
    try:
        subprocess.run(["gh", "auth", "status"], capture_output=True, timeout=10)
    except FileNotFoundError:
        print("Error: 'gh' (GitHub CLI) is required but not found.", file=sys.stderr)
        print("Install it from https://cli.github.com/", file=sys.stderr)
        sys.exit(1)

    # Fetch releases
    print("Fetching IntelliJ IDEA releases...")
    ij_releases = fetch_intellij_releases(channels)
    print(f"  Found {len(ij_releases)} IntelliJ releases")

    print("Fetching Android Studio releases...")
    as_releases = fetch_android_studio_releases(channels)
    print(f"  Found {len(as_releases)} Android Studio releases")

    all_releases = ij_releases + as_releases

    if not all_releases:
        print(f"\nNo releases found for channels: {args.channels}")
        return

    print(f"\nTotal: {len(all_releases)} IDE releases")
    print(f"Filtering to Kotlin >= {args.min_kotlin}")

    # Deduplicate by platform build number — multiple IDE entries can share the
    # same underlying build, so we only need to resolve each build once.
    unique_builds = OrderedDict()  # platform_build -> [release_dicts]
    for release in all_releases:
        pb = release["platform_build"]
        if pb not in unique_builds:
            unique_builds[pb] = []
        unique_builds[pb].append(release)

    print(f"Unique platform builds: {len(unique_builds)}")
    print()

    # Cache: nearest tag per platform major (to avoid repeated gh api calls)
    nearest_tag_cache = {}  # major -> tag or None
    # Track platform majors known to be below min_kotlin — once we resolve one
    # build in a major and it's too old, all builds in that major will be too.
    skipped_majors = set()

    def resolve_tag_for_build(build):
        """Try exact idea/<build> tag, fall back to nearest tag for the major."""
        tag = f"idea/{build}"
        kv = fetch_kotlin_version(tag)
        if kv:
            return tag, kv

        major = build.split(".")[0]
        if major not in nearest_tag_cache:
            nearest_tag_cache[major] = find_nearest_tag(major)
        nearest = nearest_tag_cache[major]
        if nearest:
            kv = fetch_kotlin_version(nearest)
            if kv:
                return nearest, kv
        return None, None

    # Cache: dev build resolution per (tag, kotlin_version) to avoid duplicate
    # gh api calls for the same tag's commit history
    dev_build_cache = {}

    # Resolve each unique platform build
    # build -> { "tag", "kotlin_version", "dev_version" }
    resolved = {}

    for build in unique_builds:
        entries = unique_builds[build]
        representative = entries[0]["ide_name"]
        major = build.split(".")[0]

        # Skip entire platform major if already known to be too old, or if
        # the major is below 221 (the first platform to bundle Kotlin)
        major_int = int(major) if major.isdigit() else 0
        if major in skipped_majors or major_int < 221:
            continue

        print(f"━━━ {representative} (build {build}) ━━━")

        tag, kotlin_version = resolve_tag_for_build(build)

        if not kotlin_version:
            print(f"  Could not resolve Kotlin version, skipping")
            skipped_majors.add(major)
            print()
            continue

        # Skip if below minimum Kotlin version
        kv_base = parse_kotlin_base_version(kotlin_version)
        if kv_base < min_kotlin:
            print(f"  Kotlin {kotlin_version} < {args.min_kotlin}, skipping")
            skipped_majors.add(major)
            print()
            continue

        print(f"  Tag: {tag}")
        print(f"  Kotlin version: {kotlin_version}")

        # Resolve to dev build (cached per tag+version)
        cache_key = (tag, kotlin_version)
        if cache_key not in dev_build_cache:
            print("  Resolving to dev build...")
            dev_build_cache[cache_key] = resolve_to_dev_build(tag, kotlin_version)
        dev_version = dev_build_cache[cache_key]
        print(f"  Dev build: {dev_version}")
        print()

        resolved[build] = {
            "tag": tag,
            "kotlin_version": kotlin_version,
            "dev_version": dev_version,
        }

    # Build alias mappings — one entry per release
    # Each entry: (ide_name, fake_version, alias_target)
    alias_entries = []

    for build, entries in unique_builds.items():
        if build not in resolved:
            continue

        info = resolved[build]
        kotlin_version = info["kotlin_version"]
        dev_version = info["dev_version"]

        for release in entries:
            if release["is_android_studio"]:
                # Android Studio: generate fake version from kotlin major.minor
                km = re.match(r"^(\d+\.\d+)", kotlin_version)
                if km:
                    fake_version = f"{km.group(1)}.255-dev-255"
                    alias_entries.append((release["ide_name"], fake_version, dev_version))
            else:
                # IntelliJ IDEA: use the ij version as alias source
                if "-ij" in kotlin_version:
                    alias_entries.append((release["ide_name"], kotlin_version, dev_version))

    # Deduplicate: group by (fake_version, alias_target)
    alias_map = OrderedDict()  # fake_version -> alias_target
    alias_comments = OrderedDict()  # fake_version -> list of IDE names

    for ide_name, fake_version, alias_target in alias_entries:
        if fake_version == alias_target:
            continue
        alias_map[fake_version] = alias_target
        if fake_version not in alias_comments:
            alias_comments[fake_version] = []
        if ide_name not in alias_comments[fake_version]:
            alias_comments[fake_version].append(ide_name)

    # Output
    print()
    print("═══════════════════════════════════════════════════════════════════════════")
    print(" RESULTS")
    print("═══════════════════════════════════════════════════════════════════════════")
    print()

    if not alias_entries:
        print("No aliases needed (all versions are already dev builds).")
        print()
        return

    # Table output — one row per unique mapping
    print(f"{'IDE (representative)':<45} {'Fake/IDE Version':<25} → Alias Target")
    print("─" * 95)
    for fake_version in sorted(alias_map.keys()):
        alias_target = alias_map[fake_version]
        names = alias_comments.get(fake_version, [])
        representative = names[0] if names else "?"
        extra = f" (+{len(names) - 1} more)" if len(names) > 1 else ""
        print(f"{representative + extra:<45} {fake_version:<25} → {alias_target}")

    print()
    print("═══════════════════════════════════════════════════════════════════════════")
    print()

    # buildConfig-ready Kotlin map
    print("Suggested buildConfig:")
    print("  mapOf(")

    for fake_version in sorted(alias_map.keys()):
        alias_target = alias_map[fake_version]
        comments = alias_comments.get(fake_version, [])
        for comment in comments:
            print(f"    // {comment}")
        print(f'    "{fake_version}" to "{alias_target}",')

    print("  )")
    print()


if __name__ == "__main__":
    main()
