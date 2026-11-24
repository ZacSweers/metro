# Features

## Familiar semantics

Metro uses familiar DI semantics from Dagger and kotlin-inject, including constructor injection, providers, multibindings, scopes, assisted injection, and `Lazy`.

## Compile-time validation

Metro validates dependency graphs at compile time.

## Compile-time FIR+IR code gen

Metro runs entirely as a Kotlin compiler plugin. It reports most declaration errors in FIR and generates code in FIR and IR. This avoids work required by source generators such as KAPT and KSP:

* It avoids extra Kotlin frontend invocations for source analysis and generation.
* It generates declarations directly in FIR and IR, which Kotlin lowers to each target platform.

Generating directly in FIR and IR also lets Metro add code to existing classes. Source generators cannot do this directly. Metro uses it for:

* Private `@Provides` declarations.
* Injection of private member properties and functions.
* Default parameter expressions for optional dependencies, including expressions that reference private APIs.

## Dagger-esque code gen and runtime

* Generated code uses factories and limits duplication, following Dagger’s approach.
* The runtime uses patterns such as `DoubleCheck` and is designed for large, modular codebases.

## Kotlin-Inject-esque API

Metro’s public API draws from kotlin-inject and uses Kotlin language features:

* Top-level function injection
* Providers live in graph interfaces or supertypes
* Native support for optional dependencies via default parameter values
* Use of Kotlin’s native `Lazy` type for lazy injections

## Coroutines Support

Metro supports suspend bindings and graph accessors, deferred initialization, scoping, and lazy initialization. Coroutines support is experimental and disabled by default. See [Coroutines Support](coroutines.md) for details.

## Anvil-esque aggregation

Metro supports Anvil-style aggregation with `@ContributesTo` and `@ContributesBinding`.

## Multiplatform

Metro supports most major Kotlin Multiplatform targets. See [Multiplatform](multiplatform.md).

## IDE Integration

The K2 IDE plugin can display FIR diagnostics when third-party compiler plugin support is enabled. See [IDE Support](installation.md#ide-support).

## Helpful diagnostics

Metro diagnostics include stable IDs, `help:` suggestions, and links to the [Diagnostics Reference](diagnostics.md). Rich console mode adds color, Unicode structure, and highlighted source snippets. See [Validation & Error Reporting](validation-and-error-reporting.md).

```
e: ExampleGraph.kt:8:3 
[Metro/MissingBinding] No binding found for Int

  trace (in test.ExampleGraph):
      Int is requested at test.ExampleGraph.int

  similar bindings:
      - @Named("qualified") Int (Different qualifier. Type: Provided) - ExampleGraph.kt:11:3
      - Number (Supertype. Type: Provided) - ExampleGraph.kt:10:3
      - Set<Int> (Multibinding. Type: Multibinding)

  help: ensure Int has an @Inject constructor or is provided by an @Provides or @Binds declaration
        visible to ExampleGraph
  docs: https://zacsweers.github.io/metro/latest/diagnostics/#missingbinding
```

For debugging, enable compiler logging or write graph reports to a directory configured in the Gradle extension.

## Advanced interop

Metro can reuse declarations and generated code from existing DI frameworks:

* Graphs can depend on Dagger and kotlin-inject components through `@Includes`.
* Custom annotation mappings let you keep annotations from another DI framework.
* Dagger interop supports Dagger, Javax, and Jakarta runtime types and can reuse Dagger or Anvil factories.
* Anvil interop recognizes `@ContributesMultibinding` and `boundType`.
