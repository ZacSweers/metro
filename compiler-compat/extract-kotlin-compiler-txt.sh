#!/bin/bash

# Extracts Kotlin compiler version information from IntelliJ IDEA or Android Studio.
#
# For Android Studio, also resolves the underlying IntelliJ version and looks up
# the real Kotlin compiler version from the intellij-community repo on GitHub.
#
# Usage:
#   ./extract-kotlin-compiler-txt.sh <path-to-IDE.app>
#   ./extract-kotlin-compiler-txt.sh /Applications/Android\ Studio\ Preview.app
#   ./extract-kotlin-compiler-txt.sh /Applications/IntelliJ\ IDEA.app

if [ -z "$1" ]; then
  echo "Usage: $0 <path-to-IDE.app>"
  echo ""
  echo "Examples:"
  echo "  $0 /Applications/Android\\ Studio\\ Preview.app"
  echo "  $0 /Applications/IntelliJ\\ IDEA.app"
  exit 1
fi

APP_PATH="$1"
CONTENTS="$APP_PATH/Contents"

if [ ! -d "$CONTENTS" ]; then
  echo "Error: Not a valid .app bundle: $APP_PATH"
  exit 1
fi

# --- Extract compiler.version from the bundled Kotlin plugin JAR ---
KOTLIN_BASE="$CONTENTS/plugins/Kotlin"
JAR="$KOTLIN_BASE/lib/kotlinc.kotlin-compiler-common.jar"

if [ ! -f "$JAR" ]; then
  echo "Error: kotlinc.kotlin-compiler-common.jar not found at $JAR"
  exit 1
fi

COMPILER_VERSION=$(unzip -p "$JAR" META-INF/compiler.version 2>/dev/null)
echo "compiler.version (from JAR): $COMPILER_VERSION"

# --- Extract build.txt from the Kotlin plugin ---
BUILD_TXT="$KOTLIN_BASE/kotlinc/build.txt"
if [ -f "$BUILD_TXT" ]; then
  echo "kotlinc build.txt: $(cat "$BUILD_TXT")"
fi

# --- Detect IDE type from product-info.json or Info.plist ---
PRODUCT_INFO="$CONTENTS/Resources/product-info.json"
IS_ANDROID_STUDIO=false
IDE_BUILD_NUMBER=""

# Try to get the build number from product-info.json first
if [ -f "$PRODUCT_INFO" ]; then
  # Extract productCode and buildNumber
  PRODUCT_CODE=$(python3 -c "import json; print(json.load(open('$PRODUCT_INFO'))['productCode'])" 2>/dev/null)
  IDE_BUILD_NUMBER=$(python3 -c "import json; print(json.load(open('$PRODUCT_INFO'))['buildNumber'])" 2>/dev/null)
  if [ "$PRODUCT_CODE" = "AI" ]; then
    IS_ANDROID_STUDIO=true
  fi
fi

# Fallback: try build.txt at the app level
if [ -z "$IDE_BUILD_NUMBER" ]; then
  APP_BUILD_TXT="$CONTENTS/Resources/build.txt"
  if [ -f "$APP_BUILD_TXT" ]; then
    IDE_BUILD_NUMBER=$(cat "$APP_BUILD_TXT")
  fi
fi

# Fallback: try Info.plist
if [ -z "$IDE_BUILD_NUMBER" ] || [ "$IS_ANDROID_STUDIO" = false ]; then
  BUNDLE_ID=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$CONTENTS/Info.plist" 2>/dev/null)
  if echo "$BUNDLE_ID" | grep -qi "android-studio\|google.android"; then
    IS_ANDROID_STUDIO=true
  fi
fi

echo ""

if [ -n "$IDE_BUILD_NUMBER" ]; then
  echo "IDE build number: $IDE_BUILD_NUMBER"
fi

if [ "$IS_ANDROID_STUDIO" = true ]; then
  echo "IDE type: Android Studio"
else
  echo "IDE type: IntelliJ IDEA"
fi

# --- Resolve the real Kotlin version for -ij builds ---
# Both Android Studio and IntelliJ IDEA can bundle -ij Kotlin versions.
# Detect this from either the compiler.version in the JAR or the build number.

# Check if the compiler version itself is an -ij version
IS_IJ_VERSION=false
if echo "$COMPILER_VERSION" | grep -qE '\-ij[0-9]'; then
  IS_IJ_VERSION=true
fi

# Parse IntelliJ version from build number if available.
# Android Studio build: AI-252.28238.7.2523.14688667 -> 252.28238.7
# IntelliJ build:       IC-252.28238.7 or IU-252.28238.7 -> 252.28238.7
INTELLIJ_VERSION=""
if [ -n "$IDE_BUILD_NUMBER" ]; then
  # Strip any prefix like "AI-", "IC-", "IU-"
  BUILD_NUMS=$(echo "$IDE_BUILD_NUMBER" | sed 's/^[A-Z]*-//')
  # Extract first 3 dot-separated segments: e.g., 252.28238.7
  INTELLIJ_VERSION=$(echo "$BUILD_NUMS" | cut -d. -f1-3)
fi

# Proceed if we have an ij compiler version or can determine the IntelliJ version
if [ "$IS_IJ_VERSION" = true ] || [ -n "$INTELLIJ_VERSION" ]; then
  echo ""

  if [ -n "$INTELLIJ_VERSION" ]; then
    echo "Underlying IntelliJ version: $INTELLIJ_VERSION"

    if [ "$IS_IJ_VERSION" = true ]; then
      # We already have the real -ij version from the JAR, no need to look it up
      echo "Real Kotlin version: $COMPILER_VERSION"
    else
      # Fake version (e.g., 2.3.255-dev-255) — look up the real version from GitHub
      GITHUB_URL="https://raw.githubusercontent.com/JetBrains/intellij-community/idea/$INTELLIJ_VERSION/.idea/libraries/kotlinc_kotlin_compiler_common.xml"
      echo ""
      echo "Looking up real Kotlin version from IntelliJ tag..."
      echo "URL: $GITHUB_URL"
      echo ""

      REAL_VERSION=$(curl -sL "$GITHUB_URL" 2>/dev/null | grep -o 'kotlin-compiler-common-for-ide:[^"]*' | head -1 | sed 's/kotlin-compiler-common-for-ide://' || true)

      if [ -n "$REAL_VERSION" ]; then
        echo "Real Kotlin version: $REAL_VERSION"
      else
        echo "Could not fetch version from GitHub."
        echo "You can check manually at:"
        echo "  https://github.com/JetBrains/intellij-community/blob/idea/$INTELLIJ_VERSION/.idea/libraries/kotlinc_kotlin_compiler_common.xml"
      fi
    fi

    # Resolve the dev build branch point if gh is available
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    RESOLVE_SCRIPT="$SCRIPT_DIR/resolve-ij-kotlin-version.sh"
    if [ -x "$RESOLVE_SCRIPT" ] && command -v gh &>/dev/null; then
      echo ""
      echo "════════════════════════════════════════════════════════════"
      echo ""
      "$RESOLVE_SCRIPT" "$INTELLIJ_VERSION" "$COMPILER_VERSION"
    fi
  elif [ "$IS_IJ_VERSION" = true ]; then
    echo "Detected -ij compiler version but could not determine IntelliJ"
    echo "build number. Run the resolve script manually:"
    echo "  ./resolve-ij-kotlin-version.sh <intellij-version> $COMPILER_VERSION"
  fi
fi
