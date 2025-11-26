# Features

## Familiar semantics

Metro builds on top of established patterns from existing DI frameworks with familiar semantics like constructor injection, providers, multibindings, scopes, assisted injection, and intrinsics like Provider/Lazy.

## Compile-time validation

Like Dagger and KI, Metro validates your dependency graph at compile-time.

## Compile-time FIR+IR code gen

Metro is implemented entirely as a Kotlin compiler plugin, primarily using FIR for error reporting and both FIR and IR for code gen. This affords significant build performance and wins compared to Dagger and KAPT/KSP in two ways:

* It avoids extra Kotlin compiler (frontend) invocations to analyze sources and generate new sources.
* It generates new code to FIR/IR directly, allowing it to be lowered directly into target platforms

FIR/IR generation allows Metro to generate code directly into existing classes, which in turn allows it to do certain things that source-generation cannot. This includes:

* Private `@Provides` declarations.
* Injection of private member properties and functions.
* Copying + reuse of default value expressions for optional dependencies, even if they reference private APIs within the source class.

## Dagger-esque code gen and runtime

* Metro’s generated code is similar to Dagger: lean, limited duplication, and practical.
* Metro’s runtime is similar to Dagger. This includes patterns like `DoubleCheck`, heavy use of factories, and an assumption that this is going to run in a large/modularized codebase.

## Kotlin-Inject-esque API

Metro’s user-facing API is similar to kotlin-inject: focused on simplicity and leaning into kotlin-language features.

* Top-level function injection
* Providers live in graph interfaces or supertypes
* Native support for optional dependencies via default parameter values
* Use of Kotlin’s native `Lazy` type for lazy injections

## Anvil-esque aggregation

Like Anvil, Metro supports contributing types via aggregation with annotations like `@ContributesTo`, `@ContributesBinding`, etc.

## Multiplatform

Metro is multiplatform and supports most major Kotlin multiplatform targets.

!!! warning
    Multi-module multiplatform targets cannot contribute dependencies (i.e. with `@Contributes*` annotations) from native targets until https://youtrack.jetbrains.com/issue/KT-75865.

## IDE Integration

Most errors are reported in FIR, which should (eventually) be visible in the K2 IDE plugin as well.

## Helpful diagnostics

Metro tries its best to give detailed-yet-readable error messages and diagnostics.

```
e: ExampleGraph.kt:8:3 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.Int

    kotlin.Int is requested at
        [test.ExampleGraph] test.ExampleGraph.int

Similar bindings:
  - @Named("qualified") Int (Different qualifier). Type: Provided. Source: ExampleGraph.kt:11:3
  - Number (Supertype). Type: Provided. Source: ExampleGraph.kt:10:3
  - Set<Int> (Multibinding). Type: Multibinding.
```

For more thorough debugging, Metro also has controls to enable debug logging controls and write graph reports to an output directory (configurable via the Gradle extension).

## Advanced interop

Metro has advanced interop with existing DI tools.

* Metro supports component-level interop with Dagger and kotlin-inject. This means that Metro graphs can depend on Dagger and kotlin-inject components via `@Includes` dependencies.
* Metro supports defining user-defined alternatives for common annotations in addition to its first-party options. This allows easier introduction to codebases using annotations from existing DI frameworks.
* If Dagger interop is enabled, Metro can generate code that both interops with Dagger/Javax/Jakarta's types directly as well as reuse Dagger/Anvil's generated factories.
* If Anvil interop is enabled, Metro's compiler can interpret Anvil's `@ContributesMultibinding` and `boundType` APIs.