# Installation

Metro is primarily applied via its companion Gradle plugin.

```kotlin
plugins {
  kotlin("multiplatform") // or jvm, android, etc
  id("dev.zacsweers.metro")
}
```

…and that’s it! This will add metro’s runtime dependencies and do all the necessary compiler plugin wiring.

If applying in other build systems, apply it however that build system conventionally applies Kotlin compiler plugins. For example with [Bazel](https://github.com/bazelbuild/rules_kotlin?tab=readme-ov-file#kotlin-compiler-plugins):

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

## IDE Support

The K2 Kotlin IntelliJ plugin supports running third party FIR plugins in the IDE, but this feature is hidden behind a flag. Some Metro features can take advantage of this, namely diagnostic reporting directly in the IDE and some opt-in features to see generated declarations. 

To enable it, do the following:

1. Enable K2 Mode for the Kotlin IntelliJ plugin.
2. Open the Registry
3. Set the `kotlin.k2.only.bundled.compiler.plugins.enabled` entry to `false`.

Note that support is unstable and subject to change.