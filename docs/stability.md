# Stability

Metro is stable and well tested. Dozens of companies use it in production, including large engineering organizations. See [Performance](performance.md) for build and runtime benchmarks.

Metro’s runtime and Gradle plugin APIs are stable and follow semantic versioning. Experimental APIs are outside that guarantee and can change or be removed.

## What about compiler plugin stability?

Metro supports a moving window of Kotlin compiler versions. See [Kotlin Compiler Compatibility](compatibility.md).

## Generated Code Stability

Generated code also depends on the Metro runtime version it was compiled against.

!!! note "Example"
    A library compiled with one Metro version may be used by a project with a different Metro runtime version.

Metro does not guarantee that generated code will work with a different runtime version from the one used to compile it. This is usually not a problem within one repository, but teams sharing compiled libraries across repositories should check runtime versions.

If you need this, I’d love to hear how you’d expect it to work. Please [start a discussion](https://github.com/ZacSweers/metro/discussions) and tell us which runtime versions you need to use together.

## Language Evolution

Metro adopts Kotlin language features when they fit the project.

- If the runtime adopts a new Kotlin feature, that Metro release requires the Kotlin version that introduced it or later. Upgrade Kotlin before upgrading Metro.

!!! note "Example"
    If Metro’s runtime used the standard library’s `AutoCloseable`, that release would require Kotlin `2.0.0` or later.

- Compiler features are supported on a best-effort basis. Supporting a feature does not necessarily raise the minimum Kotlin version.

!!! note "Example"
    Metro supports context parameters on Kotlin `2.2.20`, where they were still experimental.

## Feature Tiers

Metro prioritizes features in three tiers.

### Tier 1

_Core features receive regular testing and support._

- Graph validation
- Compiler code gen
- Runtime
- Interop
- Diagnostics
- MetroX artifacts
- Incremental compilation
- IDE support for stable IntelliJ/Android Studio versions.
- Multiplatform support (runtime and compiler)
- Compatibility with multiple Kotlin compiler versions

### Tier 2

_Debugging tools receive less testing._

- Reporting (i.e. `reportsDestination`)
- Graph analysis _Gradle_ APIs (visualization, JSON reporting, etc.)

### Tier 3

_These are generally nice-to-haves. You are welcome to file issues about them but they may not get looked at with any urgency._

- Preview IDE versions (Android Studio canaries, IntelliJ EAPs, etc.)
