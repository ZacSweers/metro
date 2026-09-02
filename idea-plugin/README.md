# Metro IDE Plugin

IDE support for Kotlin projects that use [Metro](https://github.com/ZacSweers/metro).

The plugin requires K2 and the Kotlin plugin. It reads Metro options from the IDE's Kotlin compiler
configuration and uses the K2 Analysis API and Kotlin stub indexes to find bindings across the project.

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

Pin a graph context in the Metro tool window to see its bindings in gutter tooltips and navigation.
The pin applies to declarations in that context and leaves the binding index unchanged.

Optional dependencies have two forms:

- An injection site is treated as optional when it carries `@OptionalBinding`/`@OptionalDependency`
  or when it is a parameter with a default value under the default optional-binding behavior. An
  optional site with no binding is shown as optional.
- `@BindsOptionalOf` (Dagger interop) exposes an `Optional<T>` binding, so a site injecting
  `Optional<T>` resolves to it.

Graph markers list the contributions a graph aggregates. A graph extension also reports its parent
graph and inherited contribution count in the tooltip and code vision. An accessor that returns a
`@GraphExtension` (or its factory) creates the child graph, so it gets no consumer marker.

> TODO: Add a screenshot of a consumer marker popup resolving an interface to a concrete binding.

### Find Usages

IntelliJ's Find Usages includes its ordinary Kotlin results and adds Metro relationships for indexed
binding sources and injection sites:

- Find Usages on a binding source, such as an `@Provides`, `@Binds`, `@Inject`, or contributed declaration, lists its consumers under **Injected at**.
- Find Usages on an injection site or graph accessor lists its selected bindings under **Provided by**.

Find Usages and editor navigation resolve bindings the same way. A pinned graph context limits
results to that context. With no pin, Find Usages combines results from all applicable graph contexts.
Plain classes and interfaces that are not indexed binding sources or injection sites use Kotlin's
standard Find Usages behavior.

> TODO: Add a screenshot of Find Usages showing the Injected at and Provided by groups alongside an ordinary Kotlin usage.

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
supplied at runtime. Explicit `@Assisted` parameters already show this in source, so they get no inlay.

Context-dependent implementation inlays use the graph context pinned in the Metro tool window.

> TODO: Add a GIF showing an implementation inlay and click-through navigation.

### Metro Tool Window

Open `View > Tool Windows > Metro`, then click **Load** to browse every graph context in the
project. The status indicator shows loading progress or waits for IDE indexing before graphs become
available. Graph extensions with different parent chains appear as separate rows.

Graph and binding data updates incrementally after relevant code changes by default. For large
projects, automatic refresh can be disabled in Metro's project settings. Editor decorations and the
tool window then keep using the last loaded graph data. Decorations follow declarations as they move
and disappear when their declarations can no longer be identified safely.

The tool window shows a stale-data warning until you click **Refresh** or reenable automatic refresh
to bring it up to date. Validation, Find Usages, and graph debug export still request current data.

Calls to `createDynamicGraph` and `createDynamicGraphFactory` appear as separate graph contexts at
their call sites. Each context uses the call's concrete binding containers to override bindings,
including in graph extensions. Multibinding contributions are combined. Equivalent calls in one
file share a context, matching the compiler. Calls from different files have separate contexts.

Use the graph selector to focus the tree on one concrete context and its reachable graph
extensions. The pin button applies that context to editor navigation and implementation inlays for
the current project session. If the selected path disappears after an index update, the plugin
returns to **All Graphs**.

Expanding a graph groups its bindings into scoped, unscoped, multibinding, and contributed
categories. The search field filters by binding key or implementation name, and double-clicking a
binding navigates to its declaration. After validation, an Unused category lists authored
`@Provides` and `@Binds` bindings that were not reached by that graph or its validated extensions.

Select a graph and use **More > Export Graph Debug Info** to write a local report to the IDE log
directory. Reports omit source bodies, absolute paths, and annotation literal values. They contain
project and type identifiers, so review the file before sharing it.

> TODO: Add a screenshot of the Metro tool window with a graph's binding categories expanded.

### On-Demand Graph Validation

Graphs can be validated from their gutter icon, the editor context menu, or the Validate action in
the Metro tool window. Validation runs in the background and checks every concrete extension context
before its parent graph.

The IDE and compiler share graph validation code. The plugin reports:

- Missing bindings with navigable request traces.
- Dependency cycles.
- Duplicate bindings and duplicate map keys.
- Empty multibindings that do not allow emptiness.
- Bindings used from incompatible graph scopes.
- Suspend-provider errors.

Suspend validation follows chains of dependencies, including calls from non-suspend accessors and
through synchronous `Provider` or `Lazy` wrappers. It reports unsupported suspend multibindings, member
injection, assisted factories, and a missing `runtime-coroutines` dependency when required. It recognizes
`suspend () -> T`, `SuspendProvider<T>`, and `SuspendLazy<T>` boundaries. Wrappers supplied by
factory-included graph dependencies pass through unchanged.

The tool window's **Last validation** pane shows your validation results, including graphs added while
automatic refresh is disabled. Closing the pane leaves the graph browser's selection and displayed
data unchanged.

The graph's gutter badge and its Validation row in the browser retain the cached result. They mark
it stale after relevant code changes until validation runs again. Unexpected plugin failures are
reported as internal plugin errors.

> TODO: Add a GIF showing a graph validation run and navigation through a missing-binding trace.

## Settings

Project settings live under `Settings > Tools > Metro`.

- Suppress unused-declaration warnings for Metro-injected declarations
- Suppress false-positive kapt configuration warnings in Metro-enabled modules
- Show binding navigation (gutter icons, code vision, inlay hints)
- Automatically refresh graphs and bindings after code changes
- Resolve bindings from compiled dependencies
- Show "assisted" inlay hints for Circuit implicit assisted types

Turning off binding navigation hides editor decorations only. The Metro tool window and explicit
graph validation remain available, and library resolution can still be configured independently.

Gutter marker categories are also toggleable under IntelliJ's gutter icon settings.

### Library Resolution

The plugin finds source bindings through Metro annotations. It can also resolve compiled dependencies
when the compiler provides enough metadata:

- Constructor-injected classes resolve from library metadata when requested.
- Contributions are found through generated Metro hint functions, as they are in `metroc`.
- Contribution-provider container objects identify their source through `@Origin`.
- `internal` contribution hints are visible only from friend/associated compilations, as defined by
  the compiler. Internal hints from other libraries are ignored.

> TODO: Add a screenshot from a sample project showing navigation into a library contribution.

## Current Limits

Bindings are first indexed by key across the project, then filtered for each graph. A graph includes
bindings from its scopes and `@GraphExtension` parents, binding containers (including transitive
`includes`), and factory `@Includes` dependencies. Contribution merging honors `excludes` on graphs
and `replaces` on contributions.

Scoped bindings must match the graph's scopes. `@DependencyGraph(X::class)` also supplies the
`@SingleIn(X::class)` scope. Popups and inlays use the per-graph view when graphs exist and fall back
to the project-wide view otherwise.

Not yet modeled:

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

See [How IDE binding resolution works](docs/resolution.md) for how Metro builds binding data in the
background and uses it to answer IDE requests.

The IDE plugin is a standalone Gradle build. Run these commands from the repository root.

Run a sandboxed IDE with the plugin installed:

```shell
./gradlew -p idea-plugin runLocalIde
```

To use a locally installed IDE:

```shell
./gradlew -p idea-plugin runLocalIde "-PintellijPlatformTesting.idePath=/Applications/Android Studio.app"
```

Compile the plugin:

```shell
./gradlew -p idea-plugin compileKotlin --quiet
```

Run plugin tests:

```shell
./gradlew -p idea-plugin test --quiet
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
