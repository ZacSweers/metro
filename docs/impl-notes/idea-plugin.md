# IntelliJ Plugin

## Goal

Give Metro users compiler-grade insight in the editor without running the compiler:

- Gutter markers, code vision, and inlays that connect bindings to consumers.
- A Metro tool window that lists each graph's members by category.
- On-demand graph validation with compiler-aligned diagnostics.
- Unused-declaration suppression for declarations only Metro-generated code calls.

The compiler and plugin reuse graph population, sorting, cycle detection, diagnostic models, and graph-level suspend binding validation from `metro-common`. IR and Analysis API adapters only extract frontend-specific metadata, select source anchors, and render shared witness paths. Differential tests compare IDEA results to checked-in compiler reports. The plugin never reads those reports in production because they do not exist when compilation fails, which is precisely when validation matters.

Sharing must not slow down the compiler. Compiler contribution merging and FIR bound-type checks
keep their existing in-place paths where a generic IDE-friendly representation would allocate or
scan more than the original compiler implementation.

Graph roots retain the compiler's single map-write fast path. Suspend validation stays out of
ordinary compilations and only builds diagnostic paths after suspend behavior actually needs them.

Anvil contribution ranking uses the same inline selection helper in FIR, IR, and the IDE. Moving
that small helper does not change compiler contribution scans, feature gates, or allocations.

The plugin is K2-only and reads Metro compiler plugin options from the IDE's Kotlin compiler
facet configuration, so custom annotations and interop options behave like they do in builds.

The shared graph algorithms also have a standalone JMH stress benchmark:

```shell
./gradlew :metro-common:jmh --quiet
```

It exercises graph sealing, root insertion, suspend propagation, and the shared IDE contribution
merge path with 100, 1,000, and 10,000 bindings. Compiler contribution processing retains its
separate in-place path. The benchmark reuses the shared graph tests' lightweight string models,
so it needs neither a running IDE nor a generated application project.
Add `-Pmetro.jmh.profileGc` to include allocation and garbage-collection measurements.

## Layers

Data flows in one direction:

```
module options
      |
      v
index (per-file shards)
      |
      v
BindingIndex (membership queries)
      |
      +--> editor features (markers, vision, inlays)
      |
      '--> validation (graph seal)
                 |
                 v
            tool window
```

### Options and settings

- `MetroIdeProjectService` / `metroIdeState()`: per-module `MetroOptions` parsed from the Kotlin
  facet's compiler plugin args, plus an options fingerprint for cache keying. A module is active
  only when Metro compiler plugin options are present and the plugin is enabled. Kotlin modules
  without Metro stay inactive even though the compiler option itself defaults to enabled.
- `MetroSettings`: project-level toggles for editor navigation, library resolution, and inlays.
  Hiding editor navigation does not disable graph browsing or validation. Library resolution
  applies to both editor features and graph tools.

### Index

- `index/MetroResolutionService.kt`: owns the project-wide `BindingIndex`. Snapshots use semantic
  compiler options, so module-specific report and trace paths do not create duplicate indexes.
  The first build finds candidate files through stub indexes. Later edits update changed files and
  any shards that depend on them, including inherited declarations and qualifier, scope, or
  map-key annotation defaults. Changes to otherwise untracked type aliases or constants refresh
  existing shards; project roots and semantic option changes force a new scan.
  Compiled-library results have a separate cache keyed by classpath, graph scopes, requested
  types, and visible modules. Production UI-thread requests schedule a cancellable smart-mode
  build instead of running Analysis API work on the UI thread.
- `index/IndexBuilder.kt`: builds one file's shard. Files are found through
  `KotlinAnnotationsIndex` by annotation short names, then resolved with the Analysis API inside
  `analyze {}` blocks. Handles graphs (including supertype member merging and library supertype
  binding callables), binding callables (companion members attribute to the enclosing container),
  inject classes, top-level function injection, contributions, assisted factories, and binding
  containers.
- `index/BindingExtraction.kt`: symbol-to-model extraction shared by source and library paths.
  Computes type keys, dependency keys, map key info, contribution ranks, and multibinding ids.
- `index/LibraryIndexPostProcessor.kt`: cross-file pass for compiled dependencies. Resolves
  binary inject classes on demand and discovers contributions from generated hint functions, the
  same way the compiler does. Generated aliases inherit their origin's Anvil contribution rank.
  Public hints only need classpath visibility; nonpublic hints still use Kotlin's friend-module
  visibility rules.

### Model

Everything the index stores is session-free. Nothing may retain a `KaSession`, `KaType`, or
`KaSymbol`. Types become `KaTypeSnapshot` (interned render strings plus `ClassId` and recursive
type arguments), annotations become `KaAnnotationSnapshot` (constructor-ordered resolved
arguments, including declared defaults), and declarations are held as `SmartPsiElementPointer`s.

- `model/BindingModel.kt`: `KaGraphNode`, `ConsumerEntry`, and friends.
- `model/KaBinding.kt`: the binding model. Mirrors the compiler's `IrBinding` + sealed subtypes.
- `model/KaContextualTypeKey.kt` / `model/KaTypeSnapshot.kt`: key model. Key equality is
  string-render equality, so both sides of any match must canonicalize the same way.
- `model/BindingIndex.kt`: the query surface. Global lookups (`bindingsByKey`, by-file buckets in
  ScatterMaps) plus per-graph membership. `contextFor(graph)` merges the extension parent chain
  into a `GraphContext` (scopes, containers, includes, excludes, supertype ids).
  - Membership filtering applies graph/module visibility, scope matching, declaration-specific
    containers, member-injection ownership, exclusions, explicit replacements, and Anvil ranks.
    Exclusions happen before replacements, and rank runs last within each graph's own scopes.
    Replacing or outranking a contribution never removes its separate injectable concrete type.

### Validation

The compiler's core graph logic lives in `metro-common` (`dev.zacsweers.metro.compiler.graph.MutableBindingGraph`, diagnostics, and suspend propagation and validation). The plugin adapts to it with classes named after their IR counterparts. Frontend-specific declaration extraction, source anchors, and stack rendering remain in the two adapters and are covered by compiler/IDE parity fixtures.

| IDE (`idea/graph/`) | Compiler (`ir/graph/`) |
|---------------------|------------------------|
| `KaBindingGraph`    | `IrBindingGraph`       |
| `KaBindingLookup`   | `BindingLookup`        |
| `KaBindingStack`    | `IrBindingStack`       |
| `KaBinding`         | `IrBinding`            |

- `graph/KaBindingGraph.kt`: one instance per seal. Feeds roots (accessors and injector keys)
  into `MutableBindingGraph.seal`, which produces missing bindings, duplicates, and cycle
  classification. Post-seal it validates aggregates (duplicate map keys, empty multibindings) and
  contributes missing-binding hints. Lookup state is cleared after sealing.
- `graph/KaBindingLookup.kt`: pull-based binding resolution. Only keys reachable from roots are
  ever looked up, which is what keeps validation proportional to the graph rather than the
  project. Aggregates synthesize per-element keys with a synthetic `@MultibindingElement`
  qualifier, matching the compiler's key swap.
- `graph/MetroGraphValidationService.kt`: the entry point. Coroutine-based
  (service `CoroutineScope`, `smartReadAction`, background progress, per-graph coalescing).
  Results are retained per graph (keyed by `ClassId` plus file, since same-FQN graphs can exist
  across modules) and survive index invalidation flagged as stale rather than vanishing.
  `validateWithExtensions` seals extensions before their parents, mirroring the compiler's
  traversal.
- `graph/SuspendBindingValidator.kt`: converts Analysis API bindings and graph requests to shared suspend-validation metadata, then turns shared issues and witness paths into navigable IDEA diagnostics. It contains no suspend validation policy. Suspend parity fixtures cover valid transitive/deferred paths and representative failures.

Validation is strictly on demand. Nothing seals during index builds or highlighting passes.

### Editor features

- `index/MetroLineMarkerProvider.kt`: binding, consumer, injector, graph contributions, and
  validate markers. Targets are captured as smart pointers during the background pass so clicks
  never resolve on the EDT. The validate marker badges the last validation outcome and runs
  validation through the tool window.
- `index/MetroCodeVisionProvider.kt`: consumer and contribution counts. Zero counts are omitted.
- `index/MetroInjectedImplementationInlayProvider.kt`: resolved-implementation and
  multibinding-count inlays, plus `assisted` hints for implicitly assisted parameters.
- `unused/`: implicit usage provider and inspection suppressor driven by the same options.

### Tool window

- `toolwindow/MetroTreeStructure.kt`: an `AbstractTreeStructure` over plain value nodes. Children
  compute on demand (a graph's bindings are only queried when expanded), display data is
  precomputed under the model's read action so rendering never touches PSI, and node identity is
  content-aware because `AsyncTreeModel` reuses equal nodes and would otherwise serve stale
  children. Categories: Scoped, Unscoped, Multibindings (grouped by aggregate id), Contributed,
  and Unused (authored bindings nothing requested, unioned with cached extension seals). Returns
  no children in dumb mode.
- `toolwindow/MetroToolWindowPanel.kt`: `StructureTreeModel` + `AsyncTreeModel`, search filter,
  validate/refresh toolbar, and post-validation selection of the result node. The tree refreshes
  when source changes, background indexing finishes, or the IDE leaves dumb mode.
- `toolwindow/ValidateMetroGraphAction.kt`: editor action plus the shared
  `openAndValidate(project, classId, file)` entry the gutter uses.

## Membership vs reachability

Two distinct questions, easy to conflate:

- Membership (`bindingsInContext`): what a graph *can* see. Static, includes every implicit
  `@Inject` class in the project. Drives the tool window categories and marker resolution.
- Reachability (a seal's `topology`): what the graph's roots actually pulled. Includes synthetic
  nodes (graph instances, aggregates, per-element keys) and excludes unrequested members.

The two never sum to each other. UI copy should not imply they do.

## Key contracts

- Graph identity is the resolved class plus its declaration file. Extension creation points and
  factory inputs retain that declaration identity so unrelated modules can declare the same FQNs
  without acquiring each other's parent chains or synthesized inputs. Binding containers follow
  the same rule and are selected through the owning graph's module resolution scope.
- Injected members belong only to graphs that construct their owner or explicitly inject it.
  Owner identity is separate from contribution origin and follows marked member-injection
  ancestors through graph extensions.
- Multibinding ids: map ids are `<mapKeyAnnotationParamType>_<canonicalValueKey>`. The key type
  is the map key annotation's parameter type verbatim. Values canonicalize through provider
  wrappers (`Provider<V>`, `Lazy<V>`, `() -> V` all join `V`'s aggregate). Both the contribution
  and accessor sides must use the same canonicalization or they silently never join.
- `@HasMemberInjections` gates supertype traversal for member injection. Metro requires the
  annotation, unlike Dagger.
- Graph supertypes merge their members into the graph through instantiated callable signatures,
  so inherited generic accessors and providers keep their concrete type arguments. Source and
  library supertype declarations are tracked as shard dependencies.
- Diagnostics use the compiler's `DiagnosticRenderer` and plain profile. Shared diagnostic builders live in `metro-common`; differential fixtures compare frontend-specific diagnostics by ID and normalized one-line title.

## Build wiring

The plugin is a composite build that substitutes `dev.zacsweers.metro:metro-common` and shades it
along with androidx.collection and androidx.tracing (both `compileOnly` in metro-common). The
kotlinx-coroutines transitively pulled by androidx.tracing is excluded at the configuration
level: the IDE ships a patched coroutines build, and shadowing it breaks at runtime with
confusing `NoSuchMethodError`s. Never add a coroutines dependency to the plugin.

## Testing

`BasePlatformTestCase` throughout, with helpers in `ktTestUtils.kt`: `configureMetroFile`
(default package and Metro star import), `setMetroOptions`, `addMetroRuntimeLibrary`, and
`withMetroLibFixtureLibrary` (a jar compiled from `src/test/data/libFixtures/` for binary
resolution tests).

`setMetroOptions()` always supplies `enabled=true` unless a test explicitly overrides it.
`clearMetroOptions()` represents a module where the Metro compiler plugin is not configured.

- `MetroResolutionServiceTest`: index construction and editor resolution.
- `MetroIndexDependenciesTest`: dependency keys, contextual keys, seal-facing queries.
- `MetroGraphValidationTest`: seal semantics per diagnostic kind, membership edge cases, caching.
- `MetroSuspendGraphValidationTest`: suspend propagation, request boundaries, member injection, assisted factories, multibindings, and runtime requirements.
- `MetroGraphValidationParityTest`: live IDEA validation compared with checked-in compiler graph reports, including suspend success and failure cases.
- `MetroToolWindowTreeTest`: tree rows, filtering, refresh identity, a full
  `StructureTreeModel`/`AsyncTreeModel` pass, dumb mode.
- `MetroLineMarkerProviderTest`, `MetroInlayProviderTest`, `MetroImplicitUsageProviderTest`.

Harness gotchas that repeat:

- Tests compute markers on the EDT. Calling `tooltipText` on non-Metro gutters triggers Kotlin's
  inheritor markers into prohibited EDT analysis, so always filter to Metro icons first. The
  index permits synchronous EDT analysis only in unit-test mode; production builds always run
  in background smart read actions.
- The daemon caches markers for unchanged files. Re-highlighting after validation requires
  `DaemonCodeAnalyzer.restart()`, same as production.
- Validation results are retained by design, so test classes call
  `MetroGraphValidationService.clearResults()` in `setUp`.

Manual verification runs a sandbox IDE via `./gradlew idea-plugin runLocalIde`, optionally
against a local IDE install (see `idea-plugin/README.md`).
