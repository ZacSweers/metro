# Metro Compiler Compatibility Layer

This module provides a compatibility layer for Metro's compiler plugin to work across different Kotlin compiler versions. As the Kotlin compiler APIs evolve and change between versions, this layer abstracts away version-specific differences.

These artifacts are published and are also shaded into Metro's compiler plugin. Consumers that need the compatibility layer directly can depend on the stable alias, which points to the newest version-specific artifact in each Metro release:

```kotlin
implementation("dev.zacsweers.metro:compiler-compat-latest:<metro-version>")
```

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

This uses git ancestry analysis in `JetBrains/kotlin` to find the exact dev build:

1. Fetches the Kotlin version (e.g., `2.2.20-ij252-24`) from `intellij-community`
2. Finds the corresponding build tag (e.g., `build-2.2.20-ij252-25`) in `JetBrains/kotlin`
3. Computes the merge-base between that tag and `master`
4. Uses binary search to find the dev tag at that merge-base (e.g., `build-2.2.20-dev-5810`)

This is more accurate than timestamp-based correlation because it uses actual git history.
Requires `gh` (GitHub CLI).

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

### Prerelease Mappings

Beta and RC releases are cut from a dev build, but they rank above every dev build of the same base version. Without a mapping, `2.5.0-Beta1` would pick the newest `2.5.0-dev-*` factory, even one that uses APIs its release branch doesn't have.

`prerelease-mappings.txt` maps each Beta and RC to a dev compat module version. The script finds the dev build at the prerelease's merge-base with master in `JetBrains/kotlin`. It picks the lowest dev module at or above that build and records the branch point in a comment. That's a best guess, so test each prerelease in CI once it's published. The file is regenerated daily by the `update-ide-mappings` workflow. You can also run it locally:

```bash
./fetch-prerelease-kotlin-versions.py
```

It only checks base versions that have dev compat modules and no stable module. Requires `python3` and `gh` (GitHub CLI).

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

dev track versions (e.g., `2.3.20-dev-5706`) are handled specially to avoid issues with divergent release tracks.

Kotlin's release process can create divergent version tracks:
- **dev builds** are from the main development branch (trunk)
- **Beta/RC builds** are cut from stable branches with different changes

For example:
- `2.3.20-dev-5706` - has API change X
- `2.3.20-Beta1` - released from a branch, has API change X + Y
- `2.3.20-dev-7791` - new dev build, has X + Z (not Y from Beta1)

Standard semantic version comparison would incorrectly say `2.3.20-dev-7791 < 2.3.20-Beta1` (because dev < BETA in maturity ordering), potentially selecting the wrong factory.

The resolution logic handles dev builds by:

1. First looking for dev track factories with the same base version (the same trunk lineage), comparing by build number.
2. If none match, comparing lower-base dev factories and non-dev factories. The highest compatible minVersion wins (e.g. a `2.4.0` stable factory outranks `2.4.0-dev-2124`).

Beta, RC, and stable builds first use the highest compatible non-dev factory with the same base version. If none matches, they use the newest dev factory with that base version. They fall back to an older non-dev factory only when no same-base factory is compatible. For example, an unmapped `2.5.0-Beta1` would use the newest `2.5.0-dev-*` factory when the newest release factory is `2.4.20`. Built-in prerelease mappings pin known Betas and RCs to a dev compat module first. See [Prerelease Mappings](#prerelease-mappings).

## Development Notes

- Always implement all interface methods, even if some are no-ops for certain versions
- Include docs explaining version-specific behavior
- Test thoroughly with the target Kotlin version before releasing
- Keep implementations focused and minimal - avoid adding version-specific extensions beyond the interface contract
