# Metro IDE Plugin

IDE support for Kotlin projects that use [Metro](https://github.com/ZacSweers/metro).

The plugin requires K2 and the Kotlin plugin. It reads Metro options from the IDE's Kotlin compiler
configuration and uses the K2 Analysis API and Kotlin stub indexes to find bindings across the project.

> TODO: Add a short GIF showing provider, consumer, and graph markers in one file.

## Features

### Create Injectable Classes and Contributions

Use **Make class injectable** from Alt+Enter on a class header to add `@Inject`. Classes with only
secondary constructors get a constructor picker when there is a choice.

An injectable class with a supertype also offers **Contribute Metro binding**. Objects can use the
same action. Choose `ContributesBinding`, `ContributesIntoSet`, or `ContributesIntoMap`, then choose
an aggregation scope. For example, an injected `RealHttpApi : HttpApi` can become:

```kotlin
@Inject
@ContributesBinding(AppScope::class)
class RealHttpApi : HttpApi
```

The action respects `@DefaultBinding` and asks for a bound type when several choices remain. Concrete
generic arguments are preserved. Scope choices come from known graphs and contributions, with an
editor field for entering another scope.

Map contributions reuse an existing map key or offer built-in and compatible custom key annotations.
Required key arguments become editor template fields. Type a value and press Tab to move to the next
field. Custom keys with required array or nested-annotation arguments can be added by hand.

Both actions support preview and undo. Closing a picker leaves the source unchanged. The contribution
suggestion is informational and can be disabled under `Settings > Editor > Inspections > Metro`.

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

At a dependency site, use **Go to Metro Binding** from the editor context menu to open its provider.
**Select in Metro** reveals the corresponding graph or binding in the tool window. These actions
request current graph data, including when automatic browser refresh is disabled.

### Binding Explanations

Use **Why this Metro binding?** on a dependency to see the candidates in a concrete graph context.
For example, it can explain why a higher-priority contribution wins, why a graph excludes a binding,
or why a qualifier prevents a match. The dialog shows the selected bindings, their alternatives,
and source navigation. **Copy Explanation** copies the complete result for sharing.

Explanations use a snapshot of graph data. Run the action again after editing code.

Compiler graph reports use the same structured reasons. Enable `reportsDestination` to include
binding explanations in exported graph metadata and `analysis.json`. Compiler reports describe
the candidates reached during compilation; the IDE can also show alternatives from its project
index. See [graph analysis](../docs/graph-analysis.md#binding-explanations).

### Optional Dependencies and Graph Extensions

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
binding navigates to its declaration. Enter and F4 also open the selected source. Autoscroll to Source
can preview selections without moving focus from the tree. After validation, an Unused category lists authored
`@Provides` and `@Binds` bindings that were not reached by that graph or its validated extensions.

Select a graph and use **More > Export Graph Debug Info** to write a local report to the IDE log
directory. Reports omit source bodies, absolute paths, and annotation literal values. They contain
project and type identifiers, so review the file before sharing it.

### Performance Tracing

Select **Enable debugging options** in the **Debugging/Experimental** section under
`Settings > Tools > Metro` to show the tracing controls. Debugging options are disabled by default.

Right-click **Refresh** in the Metro tool window and select **Refresh with tracing**. Recording starts
before the refresh is submitted, follows that request through retries and index publication, then
saves after admitted work finishes. Later editor requests can happen after capture completion.
A 10-minute safety deadline ends admission and marks the capture as partial if the refresh is still
running.

For other operations, use **Start Metro Performance Trace**, reproduce the issue, and select
**Stop Metro Performance Trace**. This capture accepts work for 60 seconds. Both capture modes keep
the existing caches and refresh policy. Stopping tracing or disabling debugging options ends
admission and lets admitted operations finish. The refresh continues independently.

The tool window shows **Tracing Metro refresh…** or **Tracing Metro work…** during recording,
**Finishing traced work…** while admitted operations drain, and **Saving Metro performance trace…**
during file output. Traces are saved locally in the IDE log directory and open in
[Perfetto](https://ui.perfetto.dev). Trace metadata can contain project, module, file, and class names.
Review the file before sharing it.

**Metro operations** shows an overview of recorded work even while collapsed. Expand it to inspect
full-duration bars such as **Analyze source declarations** and **Resolve source class dependencies**.
Selecting a bar shows its outcome, cache counts, and request identity. File and class summaries report
how many item bars the capture shows and omits, with elapsed time for each group. Each file and class
request gets a named duration bar while the capture budget has room. The 20 slowest items in each
phase also include detailed nested stages and a rank.

Enable **Include thread activity** in the same settings section to add IDE thread slices and coroutine
flow arrows alongside **Metro operations**. This option is off by default and applies to new captures.
Thread activity increases recording overhead and trace size; the full-duration Metro operation bars
remain available in the same trace.
Expand the Java process and its threads, then select a coroutine slice to inspect its flow arrows.
Its `operation_id` links the initial thread section to the final Metro operation bar.

The slowest files include annotation lookup, declaration extraction, dynamic-graph scanning, and shard
construction. Class requests separate analysis setup, symbol lookup, source checks, qualifier/options
lookup, cache access, binding construction, and dependency expansion. Stage totals include all measured
items and appear on phase and module summaries. Detailed stage intervals are limited to 64 per retained
item; the item's metadata reports any omitted intervals. The logical timeline retains at most 20,000 events, with
space reserved for slow-item details, enclosing phases, and summaries. Item summaries distinguish bars
omitted by the capture limit from omitted stage detail. **Trace summary** reports omitted events.

Durations measure wall time, including suspension. `read_elapsed_ns` measures time inside read-action
callbacks; it includes canceled attempts and can include Kotlin analysis waits. Item bars
also report `canceled_read_elapsed_ns` and `outside_read_ns`. Parent durations include their children.
Use `debug.operation`, `debug.operation_id`, and `debug.parent_operation_id` for SQL analysis; display
names include human-readable subjects. Duration bars carry final timing and outcome metadata.

> TODO: Add a screenshot of the Metro tool window with a graph's binding categories expanded.

### Graph Validation

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

Completed diagnostics also appear in the editor and IntelliJ's Problems view. Stale diagnostics
disappear from the editor until validation runs again. The **Last validation** pane keeps its
previous result visible with a stale label. Inspection severity can be configured under
`Settings > Editor > Inspections > Metro`.

An empty multibinding can offer **Allow an empty multibinding**, which adds
`@Multibinds(allowEmpty = true)` or changes a literal `false` to `true`. The fix supports preview and
undo. It is available for a single editable Metro declaration with current validation results.

To check one graph as you work, enable **Automatically validate the pinned graph after code changes**
in Metro settings and pin its context. The plugin validates that graph and its children after a short
pause. Unpinning, entering IDE indexing, or disabling automatic graph refresh cancels this work.
Explicit validation starts immediately.

> TODO: Add a GIF showing a graph validation run and navigation through a missing-binding trace.

## Settings

Project settings live under `Settings > Tools > Metro`.

- Suppress unused-declaration warnings for Metro-injected declarations
- Suppress false-positive kapt configuration warnings in Metro-enabled modules
- Show binding navigation (gutter icons, code vision, inlay hints)
- Automatically refresh graphs and bindings after code changes
- Automatically validate the pinned graph after code changes (off by default)
- Resolve bindings from compiled dependencies
- Show "assisted" inlay hints for Circuit implicit assisted types
- Enable debugging options (off by default)
- Include thread activity in new captures (off by default; shown when debugging options are enabled)

Turning off binding navigation hides editor decorations only. The Metro tool window and explicit
graph validation remain available, and library resolution can still be configured independently.

Gutter marker categories are also toggleable under IntelliJ's gutter icon settings.

### Library Resolution

The plugin finds source bindings through Metro annotations. It can also resolve compiled dependencies
when the compiler provides enough metadata:

- Constructor-injected classes resolve from library metadata when requested.
- Contributions are found through generated Metro hint functions, as they are in `metroc`.
- Contribution-provider container objects identify their source through `@Origin`.
- Contributed interfaces expose their accessors, providers, and member injectors in the owning graph.
- Referenced graph extensions and contributed factories include their child graphs and factory inputs.
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
- Quick fixes beyond allowing a single empty multibinding.
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
