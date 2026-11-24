# Installation

## Build Systems

Apply Metro with its **Gradle** plugin.

For other build systems, configure Metro as a Kotlin compiler plugin.

### Gradle

```kotlin
plugins {
  kotlin("multiplatform") // or jvm, android, etc
  id("dev.zacsweers.metro")
}
```

And that’s it! The plugin adds Metro’s runtime dependencies and configures the compiler plugin.

### [Bazel](https://github.com/bazelbuild/rules_kotlin?tab=readme-ov-file#kotlin-compiler-plugins)

```starlark
load("@rules_kotlin//kotlin:core.bzl", "kt_compiler_plugin")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

kt_compiler_plugin(
    name = "metro_plugin",
    compile_phase = True,
    id = "dev.zacsweers.metro.compiler",
    options = {
        "enabled": "true",
        "debug": "false",
        # ...
    },
    deps = [
        "@maven//:dev_zacsweers_metro_compiler",
    ],
)

kt_jvm_library(
    name = "sample",
    # The SampleGraph class is annotated with @DependencyGraph
    srcs = ["SampleGraph.kt"],
    plugins = [
        ":metro_plugin",
    ],
    deps = [
        "@maven//:dev_zacsweers_metro_runtime_jvm",
    ],
)
```

### [Kotlin Toolchain](https://kotlin-toolchain.org/)

Configure Metro through [Kotlin Toolchain’s compiler plugin support](https://kotlin-toolchain.org/latest/user-guide/advanced/kotlin-compiler-plugins/) (formerly Amper):

```yaml
dependencies:
  - $libs.metro.runtime

settings:
  kotlin:
    compilerPlugins:
      - id: dev.zacsweers.metro.compiler
        dependency: $libs.metro.compiler
        options:
          enabled: true
          debug: false
          # ...
```

```toml
# Version Catalog
metro-compiler = { module = "dev.zacsweers.metro:compiler", version.ref = "metro" }
metro-runtime = { module = "dev.zacsweers.metro:runtime", version.ref = "metro" }
```

## Companion blocks

Kotlin 2.5 introduces experimental companion blocks. A `companion { }` block declares static members directly in a class or interface, so you don't need a companion object to hold them. On Kotlin 2.5.0-Beta1 and later, you can put `@Provides` functions and properties in these blocks.

```kotlin
@BindingContainer
interface AppBindings {
  companion {
    @Provides fun endpoint(): String = "https://example.com"
  }
}
```

Companion blocks are off by default. Enable them with Kotlin's `-Xcompanion-blocks` or `-Xcompanion-blocks-and-extensions` option in every compilation that uses them.

!!! warning
    Kotlin 2.5.0-Beta1 has a [JVM bytecode issue](https://youtrack.jetbrains.com/issue/KT-85853) with backing fields in interface companion blocks. Until it's fixed, give properties in interface companion blocks a getter, like `val endpoint: String get() = "..."`. Properties in class companion blocks can use backing fields.

### Generated creators and helpers

Metro generates graph creators, like the `AppGraph()` call for a graph without a factory. It also generates helper functions for its factory and member injector classes. By default, all of these live on companion objects, even after you enable companion blocks. To move them into companion blocks, set Metro's `companion-mode` option:

```kotlin
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcompanion-blocks")
  }
}

metro.compilerOptions {
  put("companion-mode", "companion-block")
}
```

`companion-mode` accepts the values below. They're case-insensitive, and you can use hyphens or underscores.

| Mode | What Metro generates |
|---|---|
| `companion-object` (default) | Graph creators and helpers on companion objects. A graph's companion object implements its factory interface or has a `factory()` function. |
| `companion-block` | Graph creators and helpers as companion-block members. Metro skips companion objects that only existed to hold them. |
| `compatibility` | The `companion-object` APIs, plus companion-block functions that delegate to them. |
| `none` | No graph creators. Factory and member-injection helpers stay on companion objects. Create graphs with `createGraph()` and `createGraphFactory()`. |

The `companion-block` and `compatibility` modes need companion blocks enabled. They also need the `generate-classes-in-ir=true` option. The Metro Gradle plugin turns it on by default for Kotlin versions that support companion blocks.

Creator names come from your graph's factory. If an interface factory declares `fun build(...)`, you call `AppGraph.build(...)`. A graph without a factory uses `AppGraph()`. A graph with an abstract class factory uses `AppGraph.factory()`.

Companion-block creators copy default values only from factory functions declared in the graph's file. If your factory inherits `build(...)` with defaults from another file, pass the arguments to `AppGraph.build(...)` or call `createGraphFactory<AppGraph.Factory>().build()`.

Metro generates some factories as objects. Kotlin doesn't allow companion blocks in objects, so these factories keep their helpers on the object in every mode.

### Libraries

If other projects use your graphs or call Metro's generated helpers, set `companion-mode` explicitly. The mode decides which APIs your library publishes. To migrate a published library, start with `compatibility`. Existing calls like `AppGraph.Companion.build(...)` keep working, and so does code that uses a graph's companion object as its factory. Projects that call companion-block members also need companion blocks enabled.

## IDE Support

The K2 Kotlin IntelliJ plugin can run third-party FIR plugins behind a registry flag. Enable this to see Metro diagnostics and opt-in generated declarations in the IDE.

To enable it:

1. Enable K2 Mode for the Kotlin IntelliJ plugin.
2. Open the Registry.
3. Set the `kotlin.k2.only.bundled.compiler.plugins.enabled` entry to `false`.

IDE support is unstable and may change.

## Manual dependency management

The Metro Gradle plugin normally adds the dependencies needed by each Metro-enabled compilation. To manage them yourself, disable automatic dependency management in the Metro DSL:

```kotlin
metro {
  automaticallyAddRuntimeDependencies.set(false)
}
```

You can also disable it for all Metro projects with the `metro.automaticallyAddRuntimeDependencies=false` Gradle property.

Add `dev.zacsweers.metro:runtime:<metro-version>` to every Metro-enabled compilation. For a Kotlin Multiplatform project, add it to `commonMain` when Metro is enabled for the shared source set. Add the following dependencies when their corresponding feature is enabled:

| Feature                 | Dependency                                               | Platform        |
|-------------------------|----------------------------------------------------------|-----------------|
| Suspend providers       | `dev.zacsweers.metro:runtime-coroutines:<metro-version>` | All platforms   |
| Runtime tracing         | `dev.zacsweers.metro:metro-trace:<metro-version>`        | JVM and Android |
| Dagger runtime interop  | `dev.zacsweers.metro:interop-dagger:<metro-version>`     | JVM and Android |
| Guice runtime interop   | `dev.zacsweers.metro:interop-guice:<metro-version>`      | JVM and Android |
| Circuit code generation | `com.slack.circuit:circuit-codegen-annotations:0.33.0`   | All platforms   |

Use the same version for Metro's compiler and runtime artifacts unless you are intentionally using the compiler version override described below.

## Advanced Usage: Decoupling compiler and Gradle plugin versions

Use the same version for all Metro artifacts by default. In a large Gradle repository, updating the Gradle plugin changes the buildscript classpath and can invalidate build caches. You can update the compiler separately to avoid that cost.

You can do this yourself with Gradle dependency substitutions. Figuring out the right Gradle APIs isn’t a fate I wish on anyone, though, so Metro has a shortcut.

Set the `metro.compilerVersionOverride` **system property** to override the compiler version passed to the Kotlin Gradle plugin:

```properties
systemProp.metro.compilerVersionOverride=<metro compiler version>
```

Or pass it on the command line with `-D`:

```bash
./gradlew :your:compileKotlin -Dmetro.compilerVersionOverride=<metro compiler version>
```

Unfortunately, this can only be done via system property at the moment.

Check the changelog for Gradle plugin changes required by the compiler version. You are responsible for keeping the versions compatible. Gradle plugin updates usually add DSL options for new compiler options.
