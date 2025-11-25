# CLAUDE.md

See @README.md, @docs, and @.github/CONTRIBUTING.md for project overview.

## Common Commands

### Building and Testing
- `./gradlew :compiler:test` - Run legacy compiler tests
- `./gradlew :compiler-tests:test` - Run new compiler tests
- `./gradlew :gradle-plugin:functionalTest` - Run Gradle integration tests
- `./gradlew -p samples check` - Run sample project tests
- `./metrow check` - Runs _all_ validation and tests in the project (tests, linting, API validation). This is expensive.

Generally you should run with `--quiet` to reduce noise. Failures would be reported to the console as needed.

### Code Quality
Don't bother running code formatting, I'll handle that in commits.

### Documentation
- `./gradlew dokkaHtml` - Generate API documentation
- `docs/` - Contains all markdown documentation

### Benchmarks
- `cd benchmark && ./run_benchmarks.sh metro` - Run performance benchmarks

## Project Architecture

Metro is a compile-time dependency injection framework implemented as a Kotlin compiler plugin with multiplatform support.

### Core Modules

**compiler/** - Kotlin compiler plugin implementation
- Uses two-phase compilation: FIR (analysis) → IR (code generation)
- `fir/` - Frontend IR extensions for K2 compiler analysis and validation
- `ir/` - IR transformers for code generation
- `graph/` - Dependency graph analysis, validation, and cycle detection
- Entry point: `MetroCompilerPluginRegistrar.kt`

**runtime/** - Multiplatform annotations and runtime support
- Public annotation APIs: `@DependencyGraph`, `@Inject`, `@Provides`, `@Binds`, `@Scope`
- `internal/` - Runtime support classes (factories, providers, double-check)
- Supports JVM, JS, Native, and WASM targets

**gradle-plugin/** - Gradle integration
- `MetroGradleSubplugin.kt` - Main plugin implementation
- Provides `metro` DSL for configuration
- Automatically wires compiler plugin and runtime dependencies

**interop-dagger/** - Dagger interoperability
- Bridge functions between Metro and Dagger provider types
- Allows gradual migration from Dagger to Metro

### Testing Strategy

**compiler/src/test** - Legacy compiler tests

**compiler-tests/** - Modern JetBrains compiler testing infrastructure
- Box tests (`data/box/`) - Full compilation and execution validation
- Diagnostic tests (`data/diagnostic/`) - Error reporting and validation
- Dump tests (`data/dump/`) - FIR/IR tree inspection and verification

To create a new test, add a source file under the appropriate directory and then run `./gradlew :compiler-tests:generateTests` to regenerate tests. This will then add a generated junit test that can be run via the standard `./gradlew :compiler-tests:test` task.

**samples/** - Real-world integration examples
- `weather-app/` - Basic multiplatform usage
- `android-app/` - Android-specific integration
- `multi-module-test/` - Complex multi-module dependency graph

### Key Files for Development

**Compiler Plugin Development:**
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/fir/` - FIR analysis extensions
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/` - Code generation transformers
- `compiler/src/main/kotlin/dev/zacsweers/metro/compiler/graph/` - Dependency graph logic

**API Changes:**
- `runtime/src/commonMain/kotlin/dev/zacsweers/metro/` - Public annotation APIs
- Update both runtime and samples when changing public APIs

**Build Configuration:**
- `gradle/libs.versions.toml` - Centralized dependency versions
- Each module has `gradle.properties` for module-specific configuration
- Root `build.gradle.kts` contains shared build logic and conventions

### Development Patterns

- **Code Generation**: Uses KotlinPoet for generating factory classes and injection code
- **Graph Analysis**: Topological sorting with cycle detection for dependency resolution
- **Multiplatform**: Maximize shared common code, platform-specific only when necessary
- **Binary Compatibility**: API validation enabled for public modules (excluding compiler internals)
- **Shadow JAR**: Compiler uses shadow JAR to avoid dependency conflicts at runtime

### Testing New Features

1. Add compiler tests in `compiler-tests/src/test/data/` using the appropriate test type
2. Test existing tests with `./gradlew :compiler:test`.
3. Test integration with samples in `samples/` directory
4. Run `./metrow check` to validate all tests and API compatibility

### Important Notes

- Kotlin compiler plugins are not stable APIs - Metro tracks Kotlin releases closely
- FIR is for analysis/validation, IR is for code generation - don't mix concerns
- Always run API validation (`apiCheck`) when changing public APIs
- Use existing test infrastructure patterns rather than creating new test types
- Don't run gradle commands with unnecessary flags like `--info`, `--no-daemon`, etc.
- Don't cd into a module directory and run gradle commands - use `./gradlew` instead from the directory that wrapper is in.
- Do not run tests automatically, prompt first.
