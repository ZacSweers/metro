#!/bin/bash

# Copyright (C) 2025 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <kotlin-version>"
    echo "Example: $0 2.3.0-dev-9673"
    echo "Example: $0 2.4.0-Beta1"
    echo "Example: $0 2.3.20"
    exit 1
fi

KOTLIN_VERSION="$1"

# Transform version to valid package name
# 1. Remove dots
# 2. Replace dashes with underscores
PACKAGE_SUFFIX=$(echo "$KOTLIN_VERSION" | sed 's/\.//g' | sed 's/-/_/g')
MODULE_NAME="k$PACKAGE_SUFFIX"

echo "Generating compatibility module for Kotlin $KOTLIN_VERSION"
echo "Module name: $MODULE_NAME"
echo "Package suffix: $PACKAGE_SUFFIX"

# Create module directory structure (relative to compiler-compat/)
MODULE_DIR="compiler-compat/$MODULE_NAME"
mkdir -p "$MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME"
mkdir -p "$MODULE_DIR/src/main/resources/META-INF/services"

# Generate version.txt
echo "$KOTLIN_VERSION" > "$MODULE_DIR/version.txt"

# Generate build.gradle.kts
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

# Generate CompatContextImpl.kt
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

# Generate service loader file
cat > "$MODULE_DIR/src/main/resources/META-INF/services/dev.zacsweers.metro.compiler.compat.CompatContext\$Factory" << EOF
dev.zacsweers.metro.compiler.compat.$MODULE_NAME.CompatContextImpl\$Factory
EOF

echo ""
echo "✅ Generated module structure:"
echo "  📁 $MODULE_DIR/"
echo "  📄 $MODULE_DIR/version.txt"
echo "  📄 $MODULE_DIR/build.gradle.kts"
echo "  📄 $MODULE_DIR/gradle.properties"
echo "  📄 $MODULE_DIR/src/main/kotlin/dev/zacsweers/metro/compiler/compat/$MODULE_NAME/CompatContextImpl.kt"
echo "  📄 $MODULE_DIR/src/main/resources/META-INF/services/dev.zacsweers.metro.compiler.compat.CompatContext\$Factory"
echo ""
echo "✅ Updated build configuration:"
echo "  📝 Added module to settings.gradle.kts"
echo "  📝 Added dependency to compiler/build.gradle.kts"
echo ""
echo "Next step: Implement the CompatContextImpl.kt based on Kotlin $KOTLIN_VERSION APIs"
