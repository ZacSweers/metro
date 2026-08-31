# AGENTS.md

See @README.md, @docs, and @.github/CONTRIBUTING.md for project overview.

## Common Commands

### Building and Testing

When running tests, run focused/neighborhood tests primarily while iterating. The test suite is large and expensive to run now.

- `./gradlew :compiler:test` - Run legacy compiler tests
- `./gradlew :compiler-tests:test` - Run new compiler tests
- `./gradlew :compiler-tests:generateTests :compiler-tests:test --tests '*MyTest*'` - Generate and run a focused compiler test in one invocation
- `./gradlew :compiler-tests:test -Pmetro.compilerTestHeapSize=5g --tests '*JsBoxTestGenerated*MyTest*'` - Run a focused JS box test with more heap, without selecting stress tests
- `./gradlew :gradle-plugin:functionalTest` - Run Gradle integration tests
- `./gradlew -p samples check` - Run sample project tests
- `./metrow check` - Runs _all_ validation and tests in the project (tests, linting, API validation). This is expensive.

Generally you should run with `--quiet` to reduce noise. Failures would be reported to the console as needed.

### IDEA Plugin

`idea-plugin/` is a separate Gradle build, not a root subproject. Run commands from the repository root with `./gradlew -p idea-plugin`:

- `./gradlew -p idea-plugin compileKotlin` - Compile the plugin
- `./gradlew -p idea-plugin compileTestKotlin` - Compile its tests without running them
- `./gradlew -p idea-plugin test --tests '*MyTest*'` - Run a focused plugin test

Do not use `:idea-plugin:...` task paths in the root build.

### Code Quality

- Don't bother running code formatting or cleaning up imports, the formatter runs in commit hooks and will handle it.
- Reuse existing infrastructure. When the codebase already has a pattern, utility, or abstraction for something, USE IT. Do not duplicate code, copy patterns into new locations, or reimplement what already exists. If you're unsure whether infra exists, grep first.

### Documentation

- `./gradlew dokkaGenerateHtml` - Generate API documentation
- `docs/` - Contains all Markdown documentation

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
- Supports JVM, JS, Native, and Wasm targets

**gradle-plugin/** - Gradle integration
- `MetroGradleSubplugin.kt` - Main plugin implementation
- Provides `metro` DSL for configuration
- Automatically wires compiler plugin and runtime dependencies

**interop-dagger/** - Dagger interoperability
- Bridge functions between Metro and Dagger provider types
- Allows gradual migration from Dagger to Metro

## Testing Strategy

**compiler/src/test** - Legacy compiler tests

**compiler-tests/** - Modern JetBrains compiler testing infrastructure
- Box tests (`data/box/`) - Full compilation and execution validation
- Diagnostic tests (`data/diagnostic/`) - Error reporting and validation
- Dump tests (`data/dump/`) - FIR/IR tree inspection and verification

To create a new test, add a source file under the appropriate directory and run `./gradlew :compiler-tests:generateTests` to regenerate the checked-in JUnit suites. Generation and testing can run separately or together with `./gradlew :compiler-tests:generateTests :compiler-tests:test --tests '*MyTest*'`. When both are requested, Gradle compiles the generated Java suites after generation. Ordinary test runs do not regenerate them automatically.

For Compose-heavy JS tests that exhaust the default 2g heap, use `-Pmetro.compilerTestHeapSize=5g` with a focused `--tests` filter. Do not use `-Pmetro.enableLargeTests` for this: that property selects only `*StressTest*` tests. See `compiler-tests/README.md` for both workflows.

**samples/** - Real-world integration examples
- `weather-app/` - Basic multiplatform usage
- `android-app/` - Android-specific integration
- `multi-module-test/` - Complex multi-module dependency graph

- Prefer fakes over mocks in tests. Do not use wiremock or mock-based testing approaches unless explicitly asked.

## Key Files for Development

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

## Development Patterns

- **Code Generation**: Uses KotlinPoet for generating factory classes and injection code
- **Graph Analysis**: Topological sorting with cycle detection for dependency resolution
- **Multiplatform**: Maximize shared common code, platform-specific only when necessary
- **Binary Compatibility**: API validation enabled for public modules (excluding compiler internals)
- **Shadow JAR**: Compiler uses shadow JAR to avoid dependency conflicts at runtime

## Testing New Features

1. Add compiler tests in `compiler-tests/src/test/data/` using the appropriate test type
2. Generate the suites and run a focused selection with `./gradlew :compiler-tests:generateTests :compiler-tests:test --tests '*MyTest*'`.
3. Test integration with samples in `samples/` directory

## Important Notes

- Kotlin compiler plugins are not stable APIs – Metro tracks Kotlin releases closely
- Metro's Protocol Buffer schemas do not require backward compatibility currently. Update writers and readers together instead of retaining legacy fields or representations.
- FIR is for analysis/validation, IR is for code generation – don't mix concerns
- Always run API validation (`apiCheck`) when changing public APIs
- Use existing test infrastructure patterns rather than creating new test types
- Don't run Gradle commands with unnecessary flags like `--info`, `--no-daemon`, etc.
- Don't cd into a module directory and run Gradle commands - use `./gradlew` instead from the directory that wrapper is in. For separate builds such as `idea-plugin/`, use the root wrapper with `-p idea-plugin`.

## Working Style

- When I ask you to fix or change something, work on the EXACT file I specify. Do not edit related files, adjacent modules, or 'nearby' code unless explicitly asked. If you're unsure which file to edit, ask first.
- NEVER explore Gradle caches, .gradle directories, or external library internals to understand dependencies. If you need to understand an external API, ask me or check official docs via WebFetch. Do not spelunk.
- Explain your plan BEFORE making edits, especially for non-trivial changes. Do not jump straight to editing files. Wait for my approval of the approach.
- Do not make changes beyond what I asked for. No 'while I'm here' improvements, no proactive additions to other files, no scope creep. If you think something adjacent should also change, mention it but don't do it.
- When I explicitly tell you to skip something, leave something commented out, or not touch a specific thing - respect that completely. Do not revisit, re-enable, or re-suggest it.
- Avoid dense expression chains with trailing Elvis fallbacks or `?: run { ... }` blocks. Prefer named local values, early returns, or small helpers when fallback logic has multiple steps.
- Avoid large stacked boolean conditions in if statements. Split them into named boolean values that describe the actual state being checked.
- Write direct statements. Do not use "X, not Y" framing or variants such as "not X but Y", "this isn't X, it's Y", or "X rather than Y". Omit irrelevant counterpoints. If both facts matter, state them in separate sentences. Example: "The compiler thresholds remain undecided." Check for and remove this framing from documentation, code comments, and assistant responses before delivering them.
- Avoid bullet catalogs made of bold category labels followed by a colon and a one-line description, such as `- **Formatting:** ...`. When explaining use cases or ideas, give each a short section with a concrete example and enough prose to explain it. Use bullets for actual lists, steps, or short items that are easier to read together.
