# Installation

## Build Systems

Metro is primarily applied via its companion **Gradle** plugin.

If applying in other build systems, apply it however that build system conventionally applies Kotlin compiler plugins.

### Gradle

```kotlin
plugins {
  kotlin("multiplatform") // or jvm, android, etc
  id("dev.zacsweers.metro")
}
```

…and that’s it! This will add metro’s runtime dependencies and do all the necessary compiler plugin wiring.

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

### [Amper](https://amper.org)

Using the [compiler plugin](https://amper.org/latest/user-guide/advanced/kotlin-compiler-plugins/) support in Amper 0.10+.

```yaml
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
```

## Companion blocks and generated APIs

Kotlin 2.5.0-Beta1 and later can place `@Provides` functions and properties in companion blocks. Enable Kotlin's `-Xcompanion-blocks` option for each compilation that uses them. `-Xcompanion-blocks-and-extensions` also enables blocks.

```kotlin
@BindingContainer
interface AppBindings {
  companion {
    @Provides fun endpoint(): String = "https://example.com"
  }
}
```

Kotlin 2.5.0-Beta1 has a JVM bytecode issue for backing fields in interface companion blocks. Use getter-only properties in interface containers on that version. Class and abstract-class containers can use backing fields.

The compiler-only `companion-mode` option controls graph entry points and helpers on generated factory and member injector classes. Enabling Kotlin's blocks feature leaves Metro's default companion mode unchanged. Configure the mode through Kotlin's `freeCompilerArgs`:

```kotlin
kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-Xcompanion-blocks",
      "-P",
      "plugin:dev.zacsweers.metro.compiler:companion-mode=companion-block",
    )
  }
}
```

| Mode | Generated APIs |
|---|---|
| `COMPANION_OBJECT` | Keeps graph companions and their factory contracts. Generated factory and injector helpers use companions. This is the default. |
| `COMPANION_BLOCK` | Generates static graph creators and static factory and injector helpers. Omits companions used solely for those generated APIs. |
| `COMPATIBILITY` | Keeps the companion APIs and adds static functions that delegate to them. |
| `NONE` | Omits graph-level creator APIs. Required internal factory and injector helpers use companions. Both creation intrinsics remain available. |

The dashed spellings `companion-object`, `companion-block`, `compatibility`, and `none` are also accepted. Values are case-insensitive. The block and compatibility modes require enabled companion blocks and Kotlin 2.5.0-Beta1 or later. Singleton factory objects keep their object-based helpers in every mode because Kotlin prohibits companion blocks inside objects.

Creator names follow the declared factory method. An interface factory with `fun build(...)` exposes `AppGraph.build(...)`. A factoryless graph exposes `AppGraph()`. An abstract-class factory exposes `AppGraph.factory()`.

Choose the mode explicitly while migrating published graph or generated helper APIs. `compatibility` keeps explicit companion calls and companion-as-factory values available. Kotlin's companion features are experimental in Beta1. Consumers must enable the applicable Kotlin feature when accessing exposed blocks. Configure these options through `freeCompilerArgs` in each compilation that needs them.

## IDE Support

The K2 Kotlin IntelliJ plugin supports running third party FIR plugins in the IDE, but this feature is hidden behind a flag. Some Metro features can take advantage of this, namely diagnostic reporting directly in the IDE and some opt-in features to see generated declarations. 

To enable it, do the following:

1. Enable K2 Mode for the Kotlin IntelliJ plugin.
2. Open the Registry
3. Set the `kotlin.k2.only.bundled.compiler.plugins.enabled` entry to `false`.

Note that support is unstable and subject to change.

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

99.9% of the time, you want all the Metro artifacts to use the same version. However, if you work in an extremely large Gradle repository, you may want to _decouple_ the Metro Gradle updates from the rest of Metro. Changing the Gradle buildscript classpath can often invalidate large parts of your build cache, which in turn can be undesirable in many situations.

If you know the right Gradle APIs, you can do this yourself through dependency substitutions. Knowing the right Gradle APIs to do some advanced dependency substitution isn't a fate I wish on anyone though, so there _is_ a shortcut available within Metro directly.

To update _just_ the Metro compiler version, you can override the version the Metro plugin tells KGP to use by specifying the `metro.compilerVersionOverride` **system property**.

```properties
systemProp.metro.compilerVersionOverride=<metro compiler version>
```

Or via command line with `-D`

```bash
./gradlew :your:compileKotlin -Dmetro.compilerVersionOverride=<metro compiler version>
```

Unfortunately, this can only be done via system property at the moment.

Note that this will require you to keep track of Gradle plugin changes that may require updating it to match Metro compiler changes. These are documented in changelogs, but you are ultimately on the hook for making sure you're using compatible versions. The Gradle plugin generally only changes when there is a new Metro compiler option that requires a corresponding DSL option.
