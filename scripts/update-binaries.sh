#!/bin/bash
set -euo pipefail

# Updates formatter binaries in config/bin/ using versions from gradle/libs.versions.toml.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOML_FILE="$REPO_ROOT/gradle/libs.versions.toml"
BIN_DIR="$REPO_ROOT/config/bin"

# Shell prefix prepended to jars so they can be executed directly.
# Locates a java executable and runs `java -jar $0`.
EXEC_PREFIX='#!/bin/sh

    if [ -n "$JAVA_HOME" ] ; then
        if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
            # IBM'\''s JDK on AIX uses strange locations for the executables
            JAVACMD=$JAVA_HOME/jre/sh/java
        else
            JAVACMD=$JAVA_HOME/bin/java
        fi
        if [ ! -x "$JAVACMD" ] ; then
            die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

    Please set the JAVA_HOME variable in your environment to match the
    location of your Java installation."
        fi
    else
        JAVACMD=java
        if ! command -v java >/dev/null 2>&1
        then
            die "ERROR: JAVA_HOME is not set and no '\''java'\'' command could be found in your PATH.

    Please set the JAVA_HOME variable in your environment to match the
    location of your Java installation."
        fi
    fi

     exec "$JAVACMD" \
       -Xmx512m \
       --add-opens java.base/java.lang=ALL-UNNAMED \
       --add-opens java.base/java.util=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
       --add-opens jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
       -jar $0 "$@"

'

# Reads a version from the [versions] section of libs.versions.toml.
read_version() {
  local key="$1"
  sed -n '/^\[versions\]/,/^\[/{s/^'"$key"' *= *"\(.*\)"/\1/p;}' "$TOML_FILE"
}

# Downloads a file with an exec prefix prepended.
download_with_prefix() {
  local url="$1"
  local output="$2"
  local tmp
  tmp="$(mktemp)"
  trap "rm -f '$tmp'" RETURN

  echo "Downloading $url"
  curl -fSL --progress-bar "$url" -o "$tmp"
  printf '%s' "$EXEC_PREFIX" | cat - "$tmp" > "$output"
  chmod +x "$output"
}

# --- ktfmt ---
update_ktfmt() {
  local version
  version="$(read_version ktfmt)"
  if [ -z "$version" ]; then
    echo "Error: ktfmt version not found in $TOML_FILE" >&2
    return 1
  fi
  echo "Updating ktfmt to $version"
  download_with_prefix \
    "https://repo1.maven.org/maven2/com/facebook/ktfmt/$version/ktfmt-$version-with-dependencies.jar" \
    "$BIN_DIR/ktfmt"
  echo "Done: ktfmt $version"
}

# --- gjf ---
update_gjf() {
  local version
  version="$(read_version gjf)"
  if [ -z "$version" ]; then
    echo "Error: gjf version not found in $TOML_FILE" >&2
    return 1
  fi
  echo "Updating gjf to $version"
  download_with_prefix \
    "https://github.com/google/google-java-format/releases/download/v$version/google-java-format-$version-all-deps.jar" \
    "$BIN_DIR/gjf"
  echo "Done: gjf $version"
}

# --- main ---
mkdir -p "$BIN_DIR"

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(ktfmt gjf)
fi

for target in "${targets[@]}"; do
  case "$target" in
    ktfmt) update_ktfmt ;;
    gjf) update_gjf ;;
    all) update_ktfmt; update_gjf ;;
    *)
      echo "Unknown target: $target" >&2
      echo "Usage: $0 [ktfmt|gjf|all]" >&2
      exit 1
      ;;
  esac
done