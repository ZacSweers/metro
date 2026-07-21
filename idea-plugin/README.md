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
- Classes with `@Inject` constructors.
- `@AssistedInject` classes and constructors.
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

Provider markers navigate to known consumers. Consumer markers navigate to the matching providers,
or show an unresolved marker when the IDE index has no binding for the key.

Optional dependencies are handled two ways:

- An injection site is treated as optional when it carries `@OptionalBinding`/`@OptionalDependency`
  or when it is a parameter with a default value under the default optional-binding behavior. An
  optional site with no binding reads as optional rather than unresolved.
- `@BindsOptionalOf` (Dagger interop) exposes an `Optional<T>` binding, so a site injecting
  `Optional<T>` resolves to it.

Graph markers list the contributions a graph aggregates. A graph extension also reports its parent
graph and inherited contribution count in the tooltip and code vision. An accessor that returns a
`@GraphExtension` (or its factory) is a creation point for the child graph rather than a dependency,
so it gets no consumer marker.

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

### Metro Tool Window

Open `View > Tool Windows > Metro` to browse every graph context in the project. Graph extensions
with different parent chains appear as separate rows rather than being merged together.

Expanding a graph groups its bindings into scoped, unscoped, multibinding, and contributed
categories. The search field filters by binding key or implementation name, and double-clicking a
binding navigates to its declaration. After validation, an Unused category lists authored
`@Provides` and `@Binds` bindings that were not reached by that graph or its validated extensions.

> TODO: Add a screenshot of the Metro tool window with a graph's binding categories expanded.

### On-Demand Graph Validation

Graphs can be validated from their gutter icon, the editor context menu, or the Validate action in
the Metro tool window. Validation runs in the background and seals every concrete extension context
before its parent graph.

The IDE adapter uses Metro's shared binding-graph implementation to report:

- Missing bindings with navigable request traces.
- Dependency cycles.
- Duplicate bindings and duplicate map keys.
- Empty multibindings that do not allow emptiness.
- Bindings used from incompatible graph scopes.
- Suspend-provider errors, including non-suspend accessors, synchronous `Provider`/`Lazy`
  boundaries, unsupported suspend multibindings, member injection, assisted factories, and a
  missing optional `runtime-coroutines` artifact.

Suspend validation uses the same transitive propagation worklist as the compiler. It recognizes
`suspend () -> T`, `SuspendProvider<T>`, and `SuspendLazy<T>` boundaries, and preserves exact
wrapper pass-through from factory-included graph dependencies.

The last result remains visible in the tool window and on the graph's gutter badge. Results are
marked stale after relevant code changes until validation runs again. Unexpected plugin failures
are reported as internal plugin errors rather than Metro graph diagnostics.

> TODO: Add a GIF showing a graph validation run and navigation through a missing-binding trace.

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

- A pinnable graph context for editor navigation and inlays. Inlays currently appear only when all
  applicable graph contexts agree; gutter popups summarize context-dependent candidates.
- Exact parity with every compiler validation and diagnostic.
- Validation-backed editor inspections and quick fixes.
- Graph diagram views.

## Known Issues

- Navigating into Compose files can surface a `ProhibitedAnalysisException: Analysis is not
  allowed: Called in the EDT thread` error banner from the bundled Compose IDE plugin's
  `ComposeFoldingBuilder` ([KMT-2432](https://youtrack.jetbrains.com/issue/KMT-2432)). This is not
  caused by this plugin — any editor open triggers it. Fixed in IntelliJ IDEA 2026.1.3; Android
  Studio has not picked up the fix yet.

## Development

The IDE plugin is a standalone Gradle build. Run these commands from the repository root.

Run a sandboxed IDE with the plugin installed:

```shell
./idea-plugin/gradlew -p idea-plugin runLocalIde
```

To use a locally installed IDE:

```shell
./idea-plugin/gradlew -p idea-plugin runLocalIde "-PintellijPlatformTesting.idePath=/Applications/Android Studio.app"
```

Compile the plugin:

```shell
./idea-plugin/gradlew -p idea-plugin compileKotlin --quiet
```

Run plugin tests:

```shell
./idea-plugin/gradlew -p idea-plugin test --quiet
```

## Icons

Conventions: filled dots are bindings, strokes are edges, green provides, blue consumes,
navy is structure, dashed means not held. Each icon has a `_dark` variant.

| Icon                                                                     | Meaning                                                                                                            | Where                                                                     |
|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| <img src="src/main/resources/icons/metro.svg" width="16"/>               | The Metro logo                                                                                                     | Tool window tab                                                           |
| <img src="src/main/resources/icons/provider.svg" width="16"/>            | A binding source. The outbound arrow: this value flows out to whatever needs it                                    | Gutter on `@Provides`/`@Binds`/injected classes; tool window binding rows |
| <img src="src/main/resources/icons/consumer.svg" width="16"/>            | A dependency site. The line meets an open circle: a binding fills it                                               | Gutter on constructor params, accessors, injectors                        |
| <img src="src/main/resources/icons/consumer_unresolved.svg" width="16"/> | A dependency site with no binding found. The dashed line: nothing connects yet                                     | Gutter                                                                    |
| <img src="src/main/resources/icons/consumer_assisted.svg" width="16"/>   | An assisted parameter. A dashed circle, assisted factory creates the assisted-inject class                         | Gutter                                                                    |
| <img src="src/main/resources/icons/graph.svg" width="16"/>               | A dependency graph declaration                                                                                     | Tool window graph rows; gutter validate icon before the first run         |
| <img src="src/main/resources/icons/contributed.svg" width="16"/>         | A contributed binding (`@ContributesBinding`, etc)                                                                 | Gutter contributions icon on graphs; tool window Contributed category     |
| <img src="src/main/resources/icons/scoped.svg" width="16"/>              | A scoped binding. Solid ring, the graph holds one instance                                                         | Tool window category                                                      |
| <img src="src/main/resources/icons/unscoped.svg" width="16"/>            | An unscoped binding. Dashed ring, a new instance every time                                                        | Tool window category                                                      |
| <img src="src/main/resources/icons/multibinding.svg" width="16"/>        | A multibinding                                                                                                     | Tool window category and aggregate rows                                   |
| <img src="src/main/resources/icons/alias.svg" width="16"/>               | A `@Binds` alias. The hollow circle delegates to the filled one, the real binding                                  | Tool window binding rows                                                  |
| <img src="src/main/resources/icons/unused.svg" width="16"/>              | An authored binding nothing requested in the last validation. Grayed and dashed: it provides, but nothing connects | Tool window Unused category                                               |
| <img src="src/main/resources/icons/graph_validated.svg" width="16"/>     | This graph's last validation passed                                                                                | Gutter validate icon; tool window Validate button                         |
| <img src="src/main/resources/icons/graph_problems.svg" width="16"/>      | This graph's last validation found problems                                                                        | Gutter validate icon                                                      |
