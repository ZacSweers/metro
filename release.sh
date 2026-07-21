#!/usr/bin/env bash

# Only enable verbose tracing for actual releases
SET_FLAGS="-exo pipefail"
for arg in "$@"; do
    if [[ "$arg" == "--help" || "$arg" == "-h" || "$arg" == "--dry-run" ]]; then
        SET_FLAGS="-eo pipefail"
        break
    fi
done
# shellcheck disable=SC2086
set $SET_FLAGS

REPO="ZacSweers/metro"
ORIGIN_URL="git@github.com:ZacSweers/metro.git"
CHANGELOG_FILE="CHANGELOG.md"

usage() {
    cat <<EOF
Usage: $0 [--dry-run] <version | --major | --minor | --patch>

  version    Explicit version to release (e.g., 1.2.3)
  --major    Bump the latest CHANGELOG.md release by one major version
  --minor    Bump the latest CHANGELOG.md release by one minor version
  --patch    Bump the latest CHANGELOG.md release by one patch version
  --dry-run  Print what would be done without making changes
  --help     Show this help message
EOF
}

fail_with_usage() {
    echo "Error: $1"
    echo ""
    usage
    exit 1
}

is_stable_version() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

is_release_version() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9][A-Za-z0-9.-]*)?$ ]]
}

# Increments an input stable version string given a version type
# usage: increment_version $version $version_type
increment_version() {
    local version=$1
    local version_type=$2
    local major minor patch

    if ! is_stable_version "$version"; then
        echo "Invalid stable version: $version"
        exit 1
    fi

    IFS=. read -r major minor patch <<< "$version"

    if [ "$version_type" = "--major" ]; then
        major=$((major+1))
        minor=0
        patch=0
    elif [ "$version_type" = "--minor" ]; then
        minor=$((minor+1))
        patch=0
    elif [ "$version_type" = "--patch" ]; then
        patch=$((patch+1))
    else
        echo "Invalid version type. Must be one of: '--major', '--minor', '--patch'"
        exit 1
    fi

    echo "$major.$minor.$patch"
}

# Gets the latest stable version from the CHANGELOG.md file.
# usage: get_latest_version $changelog_file
get_latest_version() {
    local changelog_file=$1

    awk '
        /^\*\*Unreleased\*\*$/ {
            seen_unreleased = 1
            next
        }
        seen_unreleased && /^[0-9]+\.[0-9]+\.[0-9]+$/ {
            print $0
            exit
        }
    ' "$changelog_file"
}

# Updates the VERSION_NAME prop in all gradle.properties files to a new value
# usage: update_gradle_properties $new_version
update_gradle_properties() {
    local new_version=$1

    find . -type f -name 'gradle.properties' | while read -r file; do
        if grep -q "^VERSION_NAME=" "$file"; then
            sed -i '' "s/^VERSION_NAME=.*/VERSION_NAME=${new_version}/" "${file}"
        fi
    done
}

# Updates the version in docs/quickstart.md
# usage: update_quickstart_version $new_version
update_quickstart_version() {
    local new_version=$1

    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "s/id(\"dev.zacsweers.metro\") version \"[^\"]*\"/id(\"dev.zacsweers.metro\") version \"${new_version}\"/g" docs/quickstart.md
    else
        sed -i "s/id(\"dev.zacsweers.metro\") version \"[^\"]*\"/id(\"dev.zacsweers.metro\") version \"${new_version}\"/g" docs/quickstart.md
    fi
}

# Extracts the current Unreleased changelog body and converts contributor links
# to plain GitHub usernames for GitHub release notes.
# usage: write_release_notes $changelog_file $output_file
write_release_notes() {
    local changelog_file=$1
    local output_file=$2

    awk '
        /^\*\*Unreleased\*\*$/ {
            in_unreleased = 1
            next
        }
        in_unreleased && /^-+$/ {
            next
        }
        in_unreleased && /^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9][A-Za-z0-9.-]*)?$/ {
            exit
        }
        in_unreleased {
            print
        }
    ' "$changelog_file" \
        | sed -E 's/\[(@[^]]+)\]\(https:\/\/github.com\/[^)]+\)/\1/g' \
        | awk '
            NF {
                seen_content = 1
            }
            seen_content {
                lines[++count] = $0
            }
            END {
                while (count > 0 && lines[count] == "") {
                    count--
                }
                for (i = 1; i <= count; i++) {
                    print lines[i]
                }
            }
        ' > "$output_file"

    if [[ ! -s "$output_file" ]]; then
        echo "Error: $changelog_file has no Unreleased release notes."
        exit 1
    fi
}

# Promotes the current Unreleased changelog body to the given release version.
# usage: promote_changelog $changelog_file $new_version
promote_changelog() {
    local changelog_file=$1
    local new_version=$2
    local release_date
    local tmp_file

    release_date=$(date +%Y-%m-%d)
    tmp_file=$(mktemp "${TMPDIR:-/tmp}/metro-changelog.XXXXXX")

    awk -v version="$new_version" -v release_date="$release_date" '
        /^\*\*Unreleased\*\*$/ {
            print
            if (getline underline) {
                print underline
            }
            if (getline next_line) {
                print ""
                print version
                print "-----"
                print ""
                print "_" release_date "_"
                print ""
                if (next_line != "") {
                    print next_line
                }
            }
            next
        }
        {
            print
        }
    ' "$changelog_file" > "$tmp_file"

    mv "$tmp_file" "$changelog_file"
}

preflight() {
    local new_version=$1
    local current_branch
    local origin_url

    if ! command -v gh >/dev/null 2>&1; then
        echo "Error: 'gh' CLI is required. Install from https://cli.github.com/"
        exit 1
    fi

    if ! gh auth status --hostname github.com >/dev/null 2>&1; then
        echo "Error: 'gh' is not authenticated for github.com."
        exit 1
    fi

    if [[ -n "$(git status --porcelain)" ]]; then
        echo "Error: working tree is dirty. Commit or stash first."
        exit 1
    fi

    current_branch=$(git branch --show-current)
    if [[ "$current_branch" != "main" ]]; then
        echo "Error: releases must be run from main, but current branch is $current_branch."
        exit 1
    fi

    origin_url=$(git remote get-url origin)
    if [[ "$origin_url" != "$ORIGIN_URL" ]]; then
        echo "Error: origin must point at $ORIGIN_URL, but was $origin_url."
        exit 1
    fi

    if git rev-parse -q --verify "refs/tags/$new_version" >/dev/null 2>&1; then
        echo "Error: local tag $new_version already exists."
        exit 1
    fi

    if git ls-remote --exit-code --tags origin "refs/tags/$new_version" >/dev/null 2>&1; then
        echo "Error: remote tag $new_version already exists."
        exit 1
    fi

    if gh release view "$new_version" --repo "$REPO" >/dev/null 2>&1; then
        echo "Error: GitHub release $new_version already exists."
        exit 1
    fi
}

DRY_RUN=false
args=()
for arg in "$@"; do
    case "$arg" in
        --help|-h)
            usage
            exit 0
            ;;
        --dry-run)
            DRY_RUN=true
            ;;
        --major|--minor|--patch)
            args+=("$arg")
            ;;
        --*)
            fail_with_usage "unknown option: $arg"
            ;;
        *)
            args+=("$arg")
            ;;
    esac
done

if [[ "${#args[@]}" -eq 0 ]]; then
    fail_with_usage "release version or bump type is required"
fi

if [[ "${#args[@]}" -gt 1 ]]; then
    fail_with_usage "expected one release version or bump type, got ${#args[@]}"
fi

version_arg=${args[0]}
PREVIOUS_VERSION=$(get_latest_version "$CHANGELOG_FILE")
if [[ -z "$PREVIOUS_VERSION" ]]; then
    echo "Error: could not find the latest stable release in $CHANGELOG_FILE."
    exit 1
fi

# Supports explicit version (e.g., 1.2.3) or increment type (--patch, --minor, --major)
if [[ "$version_arg" == "--major" || "$version_arg" == "--minor" || "$version_arg" == "--patch" ]]; then
    NEW_VERSION=$(increment_version "$PREVIOUS_VERSION" "$version_arg")
    echo "Latest $CHANGELOG_FILE release is $PREVIOUS_VERSION; ${version_arg#--} bump is $NEW_VERSION"
else
    NEW_VERSION="${version_arg#v}"
    if ! is_release_version "$NEW_VERSION"; then
        echo "Error: version must be semver, e.g. 1.2.3"
        exit 1
    fi
fi
NEW_VERSION_BASE=${NEW_VERSION%%-*}
NEXT_SNAPSHOT_VERSION="$(increment_version "$NEW_VERSION_BASE" --minor)-SNAPSHOT"

if $DRY_RUN; then
    echo "Dry run - would perform the following steps:"
    echo "  Previous release: $PREVIOUS_VERSION"
    echo "  New release: $NEW_VERSION"
    if is_stable_version "$NEW_VERSION"; then
        echo "  1. Update gradle.properties and quickstart.md to $NEW_VERSION"
    else
        echo "  1. Update gradle.properties to $NEW_VERSION"
    fi
    echo "  2. Promote $CHANGELOG_FILE Unreleased notes to $NEW_VERSION"
    echo "  3. Generate GitHub release notes from $CHANGELOG_FILE"
    echo "  4. Run ./metrow regen"
    echo "  5. Run ./scripts/update-compatibility-docs.sh"
    echo "  6. Commit: 'Prepare for release $NEW_VERSION.'"
    echo "  7. Tag: $NEW_VERSION"
    echo "  8. Run ./metrow publish"
    echo "  9. Update gradle.properties to $NEXT_SNAPSHOT_VERSION"
    if is_stable_version "$NEW_VERSION"; then
        echo "  10. Run ./metrow regen --release-baselines"
    else
        echo "  10. Run ./metrow regen (retain stable release baselines)"
    fi
    echo "  11. Commit: 'Prepare next development version.'"
    echo "  12. Push main and $NEW_VERSION to origin ($ORIGIN_URL)"
    echo "  13. Create GitHub release:"
    echo "      gh release create $NEW_VERSION --repo $REPO --verify-tag --title $NEW_VERSION --notes-file <notes-file> --generate-notes --notes-start-tag $PREVIOUS_VERSION"
    exit 0
fi

preflight "$NEW_VERSION"

notes_file=$(mktemp "${TMPDIR:-/tmp}/metro-release-notes.XXXXXX")
trap 'rm -f "$notes_file"' EXIT
write_release_notes "$CHANGELOG_FILE" "$notes_file"

echo "Publishing $NEW_VERSION"

# Prepare release
update_gradle_properties "$NEW_VERSION"
if is_stable_version "$NEW_VERSION"; then
    update_quickstart_version "$NEW_VERSION"
fi
promote_changelog "$CHANGELOG_FILE" "$NEW_VERSION"

./metrow regen

# Update compatibility docs with tested versions
./scripts/update-compatibility-docs.sh

git commit -am "Prepare for release $NEW_VERSION."
git tag -a "$NEW_VERSION" -m "Version $NEW_VERSION"

# Publish
./metrow publish

# Prepare next snapshot
echo "Setting next snapshot version $NEXT_SNAPSHOT_VERSION"
update_gradle_properties "$NEXT_SNAPSHOT_VERSION"

if is_stable_version "$NEW_VERSION"; then
    ./metrow regen --release-baselines
else
    ./metrow regen
fi

git commit -am "Prepare next development version."

# Push it all up
git push origin main
git push origin "$NEW_VERSION"

gh release create "$NEW_VERSION" \
    --repo "$REPO" \
    --verify-tag \
    --title "$NEW_VERSION" \
    --notes-file "$notes_file" \
    --generate-notes \
    --notes-start-tag "$PREVIOUS_VERSION"
