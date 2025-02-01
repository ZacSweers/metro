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
* Native support for optional bindings via default parameter values
* Use of Kotlin’s native `Lazy` type for lazy injections

## Anvil-esque aggregation

Like Anvil, Metro supports contributing types via aggregation with annotations like `@ContributesTo`, `@ContributesBinding`, etc.

## Multiplatform

Metro is multiplatform and supports most major Kotlin multiplatform targets.

## IDE Integration

Most errors are reported in FIR, which should (eventually) be visible in the K2 IDE plugin as well.

## Compelling interop

* Metro supports component-level interop with Dagger and kotlin-inject. This means that Metro graphs can depend on Dagger and kotlin-inject components.
* Metro supports defining user-defined alternatives for common annotations in addition to its first-party options. This allows easier introduction to codebases using annotations from existing DI frameworks.
