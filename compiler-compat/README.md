# Metro Compiler Compatibility Layer

This module provides a compatibility layer for Metro's compiler plugin to work across different Kotlin compiler versions. As the Kotlin compiler APIs evolve and change between versions, this layer abstracts away version-specific differences.

This collection of artifacts is not published and is instead shaded into Metro's compiler plugin.

## Overview

The Kotlin compiler plugin APIs are not stable and can change between versions. Some APIs get deprecated, renamed, or removed entirely. This compatibility layer provides a uniform interface (`CompatContext`) that Metro's compiler can use regardless of the underlying Kotlin version.

## IDE Plugin

The Kotlin IDE plugin bundles its own compiler copy and can be checked at `lib/kotlinc.kotlin-compiler-common.jar/META-INF/compiler.version`.

IDE plugins can be downloaded from https://plugins.jetbrains.com/plugin/6954-kotlin/versions/stable.

Note this version may not have published artifacts anywhere, so it may require picking the nearest one and specifying the appropriate `minVersion` in its factory.

### Extracting Compiler Version from IDE

Use the provided script to extract the bundled Kotlin compiler version from an Android Studio or IntelliJ installation:

```bash
./extract-kotlin-compiler-txt.sh "/path/to/Android Studio.app"
```

This prints the compiler version (e.g., `2.2.255-dev-255`) to stdout.

### Resolving Dev Build for an IntelliJ Version

Use `resolve-ij-kotlin-version.sh` to trace an `-ij`-suffixed Kotlin version back to the dev build it branched from:

```bash
./resolve-ij-kotlin-version.sh 252.28238.7
./resolve-ij-kotlin-version.sh 252.28238.7 2.3.255-dev-255
```

This walks the commit history of `kotlinc_kotlin_compiler_common.xml` in `JetBrains/intellij-community` to find the last `-dev-` build before the `-ij` branch was cut. Requires `gh` (GitHub CLI).

### Fetching All IDE Kotlin Version Aliases

Use `fetch-all-ide-kotlin-versions.py` to enumerate recent IntelliJ IDEA and Android Studio releases and resolve their bundled Kotlin versions to alias mappings:

```bash
# Default: all channels, platform >= 251
./fetch-all-ide-kotlin-versions.py

# Filter channels
./fetch-all-ide-kotlin-versions.py --channels stable,canary

# Include older platforms
./fetch-all-ide-kotlin-versions.py --min-major 243
```

This fetches release metadata from the JetBrains API and Google's Android Studio updates feed, then resolves each platform build to its Kotlin version via `intellij-community` tags on GitHub. The output includes a copy-pasteable `mapOf(...)` for `BUILT_IN_COMPILER_VERSION_ALIASES` in `build.gradle.kts`.

Requires `python3` and `gh` (GitHub CLI).

## Architecture

### Core Interface

The `CompatContext` interface defines the contract for version-specific operations.

### Version-Specific Implementations

Each supported Kotlin version has its own module with a corresponding implementation:

- `k2220/` - Kotlin 2.2.20 compatibility
- `k230_dev9673/` - Kotlin 2.3.0-dev-9673 compatibility
- etc etc.

Each module contains:
- `CompatContextImpl` - Version-specific implementation
- `Factory` - Creates instances for that Kotlin version
- Service loader configuration in `META-INF/services/`

### Service Discovery

The compatibility layer uses Java's `ServiceLoader` mechanism to discover available implementations at runtime. This allows Metro to automatically select the appropriate implementation based on the available Kotlin version.

## Adding Support for New Kotlin Versions

### Automatic Generation

Use the provided script to generate a skeleton for a new Kotlin version:

```bash
cd compiler-compat
./generate-compat-module.sh 2.4.0-Beta1
```

This will create:
- Module directory structure (`k240_Beta1/`)
- Build configuration files
- Skeleton implementation with TODOs
- Service loader configuration

1. **Implement the compatibility methods:**
   Edit the generated `CompatContextImpl.kt` and replace the `TODO()` calls with actual implementations based on the available APIs in that Kotlin version.

2. **Test the implementation:**
   Run the compiler tests with the new Kotlin version to ensure compatibility.

### Version Naming Convention

The script automatically converts Kotlin versions to valid JVM package names:

- Dots are removed: `2.3.0` → `230`
- Dashes become underscores: `2.3.0-dev-9673` → `230_dev_9673`
- Module name gets `k` prefix: `k230_dev_9673`

Examples:
- `2.3.20` → `k2320`
- `2.4.0-Beta1` → `k240_Beta1`
- `2.5.0-dev-1234` → `k250_dev_1234`

## Runtime Selection

Metro's compiler plugin uses `ServiceLoader` to discover and select the appropriate compatibility implementation at runtime.

This allows Metro to support multiple Kotlin versions without requiring separate builds or complex version detection logic.

### Track-Based Resolution

dev track versions (e.g., `2.3.20-dev-5437`) are handled specially to avoid issues with divergent release tracks.

Kotlin's release process can create divergent version tracks:
- **dev builds** are from the main development branch (trunk)
- **Beta/RC builds** are cut from stable branches with different changes

For example:
- `2.3.20-dev-5437` - has API change X
- `2.3.20-Beta1` - released from a branch, has API change X + Y
- `2.3.20-dev-7791` - new dev build, has X + Z (not Y from Beta1)

Standard semantic version comparison would incorrectly say `2.3.20-dev-7791 < 2.3.20-Beta1` (because dev < BETA in maturity ordering), potentially selecting the wrong factory.

The resolution logic handles this by:
1. If the current version is a dev build, first look for dev track factories only
2. Compare only within the dev track (by build number)
3. If no dev factory matches, fall back to non-dev factories

This ensures dev builds use dev-specific factories when available, and Beta/RC/Stable versions never accidentally use dev factories.

## Development Notes

- Always implement all interface methods, even if some are no-ops for certain versions
- Include docs explaining version-specific behavior
- Test thoroughly with the target Kotlin version before releasing
- Keep implementations focused and minimal - avoid adding version-specific extensions beyond the interface contract
