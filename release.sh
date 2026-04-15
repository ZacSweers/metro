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

# Gets a property out of a .properties file
# usage: getProperty $key $filename
function getProperty() {
    grep "${1}" "$2" | cut -d'=' -f2
}

# Increments an input version string given a version type
# usage: increment_version $version $version_type
increment_version() {
    local delimiter=.
    local array=()
    while IFS='' read -r line; do array+=("$line"); done < <(echo "$1" | tr $delimiter '\n')
    local version_type=$2
    local major=${array[0]}
    local minor=${array[1]}
    local patch=${array[2]}

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

    incremented_version="$major.$minor.$patch"

    echo "${incremented_version}"
}

# Gets the latest version from the CHANGELOG.md file. Note this assumes the changelog is updated with the
# new version as the latest, so it gets the *2nd* match.
# usage: get_latest_version $changelog_file
get_latest_version() {
    local changelog_file=$1
    grep -m 2 -o '^[0-9]\+\.[0-9]\+\.[0-9]\+' "$changelog_file" | tail -n 1
}

# Updates the VERSION_NAME prop in all gradle.properties files to a new value
# usage: update_gradle_properties $new_version
update_gradle_properties() {
    local new_version=$1

    find . -type f -name 'gradle.properties' | while read -r file; do
        if grep -q "VERSION_NAME=" "$file"; then
            local prev_version
            prev_version=$(getProperty 'VERSION_NAME' "${file}")
            sed -i '' "s/${prev_version}/${new_version}/g" "${file}"
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

# Updates the metro version in gradle/libs.versions.toml
# usage: update_libs_versions_metro $new_version
update_libs_versions_metro() {
    local new_version=$1
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "s/^metro = \"[^\"]*\"/metro = \"${new_version}\"/" gradle/libs.versions.toml
    else
        sed -i "s/^metro = \"[^\"]*\"/metro = \"${new_version}\"/" gradle/libs.versions.toml
    fi
}

# Parse flags
DRY_RUN=false
args=()
for arg in "$@"; do
    case "$arg" in
        --help|-h)
            echo "Usage: $0 [--dry-run] [version | --major | --minor | --patch]"
            echo ""
            echo "  version    Explicit version to release (e.g., 1.2.3)"
            echo "  --major    Bump the major version"
            echo "  --minor    Bump the minor version"
            echo "  --patch    Bump the patch version (default)"
            echo "  --dry-run  Print what would be done without making changes"
            echo "  --help     Show this help message"
            exit 0
            ;;
        --dry-run)
            DRY_RUN=true
            ;;
        *)
            args+=("$arg")
            ;;
    esac
done

# Supports explicit version (e.g., 1.2.3) or increment type (--patch, --minor, --major)
version_arg=${args[0]:---patch}
if [[ "$version_arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    NEW_VERSION="$version_arg"
else
    LATEST_VERSION=$(get_latest_version CHANGELOG.md)
    NEW_VERSION=$(increment_version "$LATEST_VERSION" "$version_arg")
fi
NEXT_SNAPSHOT_VERSION="$(increment_version "$NEW_VERSION" --minor)-SNAPSHOT"

if $DRY_RUN; then
    echo "Dry run — would perform the following steps:"
    echo "  1. Update gradle.properties, quickstart.md, libs.versions.toml to $NEW_VERSION"
    echo "  2. Run ./metrow regen"
    echo "  3. Run ./scripts/update-compatibility-docs.sh"
    echo "  4. Commit: 'Prepare for release $NEW_VERSION.'"
    echo "  5. Tag: $NEW_VERSION"
    echo "  6. Run ./metrow publish"
    echo "  7. Update gradle.properties to $NEXT_SNAPSHOT_VERSION"
    echo "  8. Run ./metrow regen"
    echo "  9. Commit: 'Prepare next development version.'"
    echo "  10. Push commits and tags"
    exit 0
fi

echo "Publishing $NEW_VERSION"

# Prepare release
update_gradle_properties "$NEW_VERSION"
update_quickstart_version "$NEW_VERSION"
update_libs_versions_metro "$NEW_VERSION"

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

./metrow regen

git commit -am "Prepare next development version."

# Push it all up
git push && git push --tags
