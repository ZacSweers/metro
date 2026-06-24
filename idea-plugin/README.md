# Metro IDE Plugin

IDE support for Kotlin projects that use [Metro](https://github.com/ZacSweers/metro).

The plugin is K2-only and depends on the Kotlin plugin. It reads Metro compiler plugin options from
the IDE's Kotlin compiler configuration, then uses the K2 Analysis API and Kotlin stub indexes to
build a project-wide binding index for editor features.

> TODO: Add a short GIF showing provider, consumer, and graph markers in one file.

## Features

### Unused Declaration Suppression

Metro-generated code can be the only caller of providers, injected classes, etc. The plugin marks those 
declarations as implicitly used so IntelliJ's unused declaration inspection does not report false positives.

Covered declarations include:

- `@Binds`, `@Provides`, and `@Multibinds` members.
- `@Inject` constructors and injectable classes.
- `@AssistedInject` classes.
- `@ContributesBinding`, `@ContributesIntoSet`, and `@ContributesIntoMap` classes.
- Graph factory `@Provides` parameters.
- Metro-native Circuit `@CircuitInject` declarations when `enable-circuit-codegen` is enabled.

Configured custom Metro annotations and supported interop annotation sets are read from the same
compiler plugin options used by the compiler.

### Binding Navigation

The plugin adds gutter icons for the standard Metro binding relationships:

- Provider markers on binding origins, such as `@Provides`, `@Binds`, `@Inject`, and contributed
  binding declarations.
- Consumer markers on injected parameters, member-injected properties, and graph accessor members.
- Graph markers on `@DependencyGraph` declarations.

Provider markers navigate to known consumers. Consumer markers navigate to matching providers or
show an unresolved marker when no binding is present in the current IDE index. Graph markers list
the contributions the graph itself aggregates; graph extensions additionally report their parent
graph and inherited contribution counts in the marker tooltip and code vision. Accessors returning
a `@GraphExtension` (or its factory) are creation points of the child graph, not dependencies, so
they get no consumer marker.

> TODO: Add a screenshot of a consumer marker popup resolving an interface to a concrete binding.

### Code Vision

Metro code vision entries summarize binding relationships above declarations:

- Providers show consumer counts.
- Graphs show contribution counts.

Clicking a code vision entry opens the same navigation popup as the corresponding gutter marker.

> TODO: Add a screenshot of code vision counts above a provider and graph.

### Injected Implementation Inlays

For injection sites declared as an interface or abstract type, the plugin can show the statically
resolved implementation inline.

```kotlin
@Inject
class CheckoutFlow(
  private val api: HttpApi,        // RealHttpApi
  private val interceptors: Set<Interceptor>, // 3 elements
)
```

Single resolved implementations are clickable and navigate to the provider. Multibindings show the
number of contributed elements or map entries. Implicitly assisted parameters, such as
Circuit-provided `Screen`/`Navigator` parameters, can show an `assisted` inlay because they are
supplied at runtime rather than injected from the graph (explicit `@Assisted` parameters already
read as assisted in source, so they get no inlay).

> TODO: Add a GIF showing an implementation inlay and click-through navigation.

## Settings

Project settings live under `Settings > Tools > Metro`.

- Suppress unused-declaration warnings for Metro-injected declarations
- Show binding navigation (gutter icons, code vision, inlay hints)
- Resolve bindings from compiled dependencies
- Show "assisted" inlay hints for Circuit implicit assisted types

Gutter marker categories are also toggleable under IntelliJ's gutter icon settings.

### Library Resolution

Project _source_ is indexed from Metro-relevant annotations. _Compiled_ dependencies are covered where
the compiler exposes enough metadata:

- Constructor-injected classes resolve on demand from library metadata.
- Contributions are discovered from generated Metro hint functions (matching how metroc discovers them).
- Contribution-provider container objects are attributed through `@Origin`.
- `internal` contribution hints respect the compiler's formal friend/associated compilation
  visibility and are filtered from non-friend external libraries.

> TODO: Add a screenshot from a sample project showing navigation into a library contribution.

## Current Limits

Resolution starts from a project-wide, key-based index and then applies per-graph membership on
top: each graph's context aggregates its scopes (following `@GraphExtension` parent chains), wired
binding containers (including transitive `includes`), factory `@Includes` dependencies,
contribution merging (`excludes` on graphs, `replaces` on contributions), and scope matching for
scoped bindings (a graph's declared scopes include the `@SingleIn(X::class)` implicitly conveyed
by `@DependencyGraph(X::class)`). Popups and inlays use the per-graph view when graphs exist and
fall back to the global view otherwise.

Not yet modeled:

- Pinnable graph context (results are unioned across graphs rather than scoped to a chosen one).
- Exact compiler graph validation parity.
- Tool window and graph diagram views.

## Known Issues

- Navigating into Compose files can surface a `ProhibitedAnalysisException: Analysis is not
  allowed: Called in the EDT thread` error banner from the bundled Compose IDE plugin's
  `ComposeFoldingBuilder` ([KMT-2432](https://youtrack.jetbrains.com/issue/KMT-2432)). This is not
  caused by this plugin — any editor open triggers it. Fixed in IntelliJ IDEA 2026.1.3; Android
  Studio has not picked up the fix yet.

## Development

Run a sandboxed IDE with the plugin installed:

```shell
./gradlew idea-plugin runLocalIde
```

To use a locally installed IDE:

```shell
./gradlew idea-plugin runLocalIde "-PintellijPlatformTesting.idePath=/Applications/Android Studio.app"
```

Compile the plugin:

```shell
./gradlew idea-plugin compileKotlin --quiet
```

Run plugin tests:

```shell
./gradlew idea-plugin test --quiet
```
