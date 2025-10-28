#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

# Ensure script is run from project root
if [ ! -f "settings.gradle.kts" ] || [ ! -d "compiler-compat" ]; then
    echo "❌ Error: This script must be run from the project root directory"
    echo "Example: ./compiler-compat/generate-compat-module.sh 2.3.0"
    exit 1
fi

# Function to display help
show_help() {
    cat << EOF
Usage: $0 [OPTIONS] <kotlin-version>

Generate a Metro compiler compatibility module for a specific Kotlin version.

Arguments:
  <kotlin-version>      Kotlin version to generate compatibility module for
                        (e.g., 2.3.0, 2.3.0-dev-9673, 2.3.21)

Options:
  -h, --help           Display this help message and exit
  --version-only       Add version to version-aliases.txt for CI support only
                        (no module generation). The version will use the nearest
                        available module implementation.
  --delegates-to       Generate module that delegates to specified version
                        (e.g., --delegates-to 2.3.0-Beta1)

Examples:
  $0 2.3.0-dev-9673                          # Generate full compatibility module
  $0 --version-only 2.3.21                   # Add CI-supported version alias only
  $0 2.3.0-Beta2 --delegates-to 2.3.0-Beta1  # Generate module that delegates to Beta1
  $0 -h                                      # Show this help message

Description:
  This script generates a new compiler compatibility module for a specific Kotlin
  version, including directory structure, build configuration, and implementation
  scaffolding. The version is automatically added to version-aliases.txt for CI.

  In --version-only mode, only version-aliases.txt is updated, allowing CI to test
  against the version using an existing compatibility module implementation.

  With --delegates-to, the generated module uses Kotlin class delegation to forward
  all CompatContext implementations to the specified version's module. This is useful
  when a new Kotlin version (e.g., Beta2) is compatible with a previous version's
  implementation (e.g., Beta1), avoiding duplicate code.

Generated Structure:
  compiler-compat/k<version>/
  ├── build.gradle.kts
  ├── version.txt
  └── src/main/
      ├── kotlin/dev/zacsweers/metro/compiler/compat/k<version>/
      │   └── CompatContextImpl.kt
      └── resources/META-INF/services/
          └── dev.zacsweers.metro.compiler.compat.CompatContext\$Factory

EOF
}

# Parse arguments
VERSION_ONLY=false
DELEGATES_TO=""
KOTLIN_VERSION=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        --version-only)
            VERSION_ONLY=true
            shift
            ;;
        --delegates-to)
            if [ -z "$2" ] || [[ "$2" == -* ]]; then
                echo "Error: --delegates-to requires a version argument"
                exit 1
            fi
            DELEGATES_TO="$2"
            shift 2
            ;;
        -*)
            echo "Unknown option: $1"
            echo "Usage: $0 [OPTIONS] <kotlin-version>"
            echo ""
            echo "Options:"
            echo "  -h, --help           Display help message"
            echo "  --version-only       Add version to version-aliases.txt for CI support (no module generation)"
            echo "  --delegates-to VER   Generate module that delegates to specified version"
            echo ""
            echo "Examples:"
            echo "  $0 2.3.0-dev-9673                          # Generate full module"
            echo "  $0 --version-only 2.3.21                   # Add CI-supported version alias only"
            echo "  $0 2.3.0-Beta2 --delegates-to 2.3.0-Beta1  # Generate delegating module"
            echo ""
            echo "Run '$0 --help' for more information."
            exit 1
            ;;
        *)
            if [ -z "$KOTLIN_VERSION" ]; then
                KOTLIN_VERSION="$1"
            else
                echo "Error: Multiple versions specified"
                exit 1
            fi
            shift
            ;;
    esac
done

if [ -z "$KOTLIN_VERSION" ]; then
    echo "Error: No Kotlin version specified"
    echo "Usage: $0 [--version-only] <kotlin-version>"
    echo ""
    echo "Options:"
    echo "  --version-only    Add version to version-aliases.txt for CI support (no module generation)"
    echo ""
    echo "Examples:"
    echo "  $0 2.3.0-dev-9673              # Generate full module"
    echo "  $0 --version-only 2.3.21       # Add CI-supported version alias only"
    exit 1
fi

# Function to add version to version-aliases.txt
add_to_version_aliases() {
    local version="$1"
    local aliases_file="compiler-compat/version-aliases.txt"

    # Check if version already exists
    if grep -Fxq "$version" "$aliases_file" 2>/dev/null; then
        echo "⚠️  Version $version already exists in $aliases_file"
        return 0
    fi

    # Add version to the file (maintain sorted order)
    echo "$version" >> "$aliases_file"

    # Re-sort the file (keeping header comments at the top)
    local tmpfile=$(mktemp)
    # Extract header (all lines until first non-comment/non-blank line)
    awk '/^[^#]/ && NF {exit} {print}' "$aliases_file" > "$tmpfile"
    # Extract and sort versions
    grep -v '^#' "$aliases_file" | grep -v '^[[:space:]]*$' | sort >> "$tmpfile"
    mv "$tmpfile" "$aliases_file"

    echo "✅ Added $version to $aliases_file"
}

# If --version-only, just add to version-aliases.txt and exit
if [ "$VERSION_ONLY" = true ]; then
    echo "Adding version $KOTLIN_VERSION to version-aliases.txt (--version-only mode)"
    add_to_version_aliases "$KOTLIN_VERSION"
    echo ""
    echo "✅ Done! Version added to version-aliases.txt for CI support"
    echo ""
    echo "Note: This version will use the nearest available module implementation."
    echo "To generate a dedicated module implementation, run without --version-only flag."
    exit 0
fi

# Transform version to valid package name
# 1. Remove dots
# 2. Replace dashes with underscores
PACKAGE_SUFFIX=$(echo "$KOTLIN_VERSION" | sed 's/\.//g' | sed 's/-/_/g')
MODULE_NAME="k$PACKAGE_SUFFIX"

echo "Generating compatibility module for Kotlin $KOTLIN_VERSION"
echo "Module name: $MODULE_NAME"
echo "Package suffix: $PACKAGE_SUFFIX"

# Handle delegation if --delegates-to is specified
DELEGATE_MODULE_NAME=""
DELEGATE_PACKAGE_SUFFIX=""
if [ -n "$DELEGATES_TO" ]; then
    # Transform delegate version to module name
    DELEGATE_PACKAGE_SUFFIX=$(echo "$DELEGATES_TO" | sed 's/\.//g' | sed 's/-/_/g')
    DELEGATE_MODULE_NAME="k$DELEGATE_PACKAGE_SUFFIX"

    # Verify delegate module exists
    if [ ! -d "compiler-compat/$DELEGATE_MODULE_NAME" ]; then
        echo "❌ Error: Delegate module 'compiler-compat/$DELEGATE_MODULE_NAME' does not exist"
        echo "Available modules:"
        ls -1 compiler-compat/ | grep '^k' || echo "  (none found)"
        exit 1
    fi

    echo "Delegating to: $DELEGATES_TO (module: $DELEGATE_MODULE_NAME)"
fi

# Create module directory structure (relative to compiler-compat/)
MODULE_DIR="compiler-compat/$MODULE_NAME"
mkdir -p "$MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME"
mkdir -p "$MODULE_DIR/src/main/resources/META-INF/services"

# Generate version.txt
echo "$KOTLIN_VERSION" > "$MODULE_DIR/version.txt"

# Generate build.gradle.kts
if [ -n "$DELEGATES_TO" ]; then
  # With delegation - add dependency on delegate module
  cat > "$MODULE_DIR/build.gradle.kts" << EOF
// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    optIn.addAll(
      "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
      "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
    )
  }
}

dependencies {
  val kotlinVersion = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }
  compileOnly(kotlinVersion.map { "org.jetbrains.kotlin:kotlin-compiler:\$it" })
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
  implementation(project(":compiler-compat:$DELEGATE_MODULE_NAME"))
}
EOF
else
  # Without delegation - standard template
  cat > "$MODULE_DIR/build.gradle.kts" << EOF
// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    optIn.addAll(
      "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
      "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
    )
  }
}

dependencies {
  val kotlinVersion = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.map { it.trim() }
  compileOnly(kotlinVersion.map { "org.jetbrains.kotlin:kotlin-compiler:\$it" })
  compileOnly(libs.kotlin.stdlib)
  api(project(":compiler-compat"))
}
EOF
fi

# Generate CompatContextImpl.kt
if [ -n "$DELEGATES_TO" ]; then
  # With delegation - delegate to the specified version's implementation
  cat > "$MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME/CompatContextImpl.kt" << EOF
// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.$MODULE_NAME

import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.$DELEGATE_MODULE_NAME.CompatContextImpl as DelegateType

public class CompatContextImpl : CompatContext by DelegateType() {
  public class Factory : CompatContext.Factory {
    override val minVersion: String = "$KOTLIN_VERSION"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
EOF
else
  # Without delegation - standard template
  cat > "$MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME/CompatContextImpl.kt" << EOF
// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat.$MODULE_NAME

import dev.zacsweers.metro.compiler.compat.CompatContext

public class CompatContextImpl : CompatContext {
  // TODO Implement

  public class Factory : CompatContext.Factory {
    override val minVersion: String = "$KOTLIN_VERSION"

    override fun create(): CompatContext = CompatContextImpl()
  }
}
EOF
fi

# Generate service loader file
cat > "$MODULE_DIR/src/main/resources/META-INF/services/dev.zacsweers.metro.compiler.compat.CompatContext\$Factory" << EOF
dev.zacsweers.metro.compiler.compat.$MODULE_NAME.CompatContextImpl\$Factory
EOF

# Add version to version-aliases.txt
add_to_version_aliases "$KOTLIN_VERSION"

echo ""
echo "✅ Generated module structure:"
echo "  📁 $MODULE_DIR/"
echo "  📄 $MODULE_DIR/version.txt"
echo "  📄 $MODULE_DIR/build.gradle.kts"
echo "  📄 $MODULE_DIR/gradle.properties"
echo "  📄 $MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME/CompatContextImpl.kt"
echo "  📄 $MODULE_DIR/src/main/resources/META-INF/services/dev.zacsweers.metro.compiler.compat.CompatContext\$Factory"
echo ""
echo "✅ Updated configuration:"
echo "  📝 Added module to settings.gradle.kts (auto-discovered)"
echo "  📝 Added dependency to compiler/build.gradle.kts (auto-discovered)"
echo "  📝 Added $KOTLIN_VERSION to compiler-compat/version-aliases.txt"

if [ -n "$DELEGATES_TO" ]; then
  echo "  📝 Added dependency on $DELEGATE_MODULE_NAME module"
  echo ""
  echo "✅ Done! Module delegates to $DELEGATES_TO implementation"
else
  echo ""
  echo "Next step: Implement the CompatContextImpl.kt based on Kotlin $KOTLIN_VERSION APIs"
fi
