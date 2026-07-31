# IntelliJ Plugin

## Goal

Give Metro users compiler-grade insight in the editor without running the compiler:

- Gutter markers, code vision, and inlays that connect bindings to consumers.
- A Metro tool window that lists each graph's members by category.
- On-demand full graph validation with the compiler's exact diagnostics.
- Unused-declaration suppression for declarations only Metro-generated code calls.

Two rules shape everything. Validation reuses the compiler's shared graph core from
`metro-common`, so validation semantics have exactly one implementation. And the plugin never
reads compiler report outputs, because those do not exist when compilation fails, which is
precisely when validation matters.

The plugin is K2-only and reads Metro compiler plugin options from the IDE's Kotlin compiler
facet configuration, so custom annotations and interop options behave like they do in builds.

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
  facet's compiler plugin args, plus an options fingerprint for cache keying.
- `MetroSettings`: project-level toggles (binding resolution, library resolution, inlays).

### Index

- `index/MetroResolutionService.kt`: owns the project-wide `BindingIndex`. Indexes are cached per
  options fingerprint in an LRU of `CachedValue`s that invalidate on any PSI change. Rebuilds are
  cheap because per-file shards are cached against each file's modification stamp, so a rebuild
  is mostly shard cache hits plus a merge. All index access requires a read action.
- `index/IndexBuilder.kt`: builds one file's shard. Files are found through
  `KotlinAnnotationsIndex` by annotation short names, then resolved with the Analysis API inside
  `analyze {}` blocks. Handles graphs (including supertype member merging and library supertype
  binding callables), binding callables (companion members attribute to the enclosing container),
  inject classes, top-level function injection, contributions, assisted factories, and binding
  containers.
- `index/BindingExtraction.kt`: symbol-to-model extraction shared by source and library paths.
  Computes type keys, dependency keys, map key info, and multibinding ids.
- `index/LibraryIndexPostProcessor.kt`: cross-file pass for compiled dependencies. Resolves
  binary inject classes on demand and discovers contributions from generated hint functions, the
  same way the compiler does.

### Model

Everything the index stores is session-free. Nothing may retain a `KaSession`, `KaType`, or
`KaSymbol`. Types become `KaTypeSnapshot` (interned render strings plus `ClassId` and recursive
type arguments), annotations become `KaAnnotationSnapshot` (structured resolved arguments with
canonical-render equality), and declarations are held as `SmartPsiElementPointer`s.

- `model/BindingModel.kt`: `KaGraphNode`, `ConsumerEntry`, and friends.
- `model/KaBinding.kt`: the binding model. Mirrors the compiler's `IrBinding` + sealed subtypes.
- `model/KaContextualTypeKey.kt` / `model/KaTypeSnapshot.kt`: key model. Key equality is
  string-render equality, so both sides of any match must canonicalize the same way.
- `model/BindingIndex.kt`: the query surface. Global lookups (`bindingsByKey`, by-file buckets in
  ScatterMaps) plus per-graph membership. `contextFor(graph)` merges the extension parent chain
  into a `GraphContext` (scopes, containers, includes, excludes, supertype ids).
  - Membership filtering (`isBindingInContext`, `isConsumerInContext`) applies scope matching, container
    wiring, excludes, and replaces. Replaces only drops the origin's contributions, never its own
    injectable type.

### Validation

The compiler's core graph logic lives in `metro-common`
(`dev.zacsweers.metro.compiler.graph.MutableBindingGraph`, diagnostics, etc.). The plugin adapts
to it with classes named after their IR counterparts. Structural parity with the IR
implementation is a deliberate maintenance rule. When in doubt, check what the IR side does and
mirror it.

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
  validate/refresh toolbar, and post-validation selection of the result node.
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

- Multibinding ids: map ids are `<mapKeyAnnotationParamType>_<canonicalValueKey>`. The key type
  is the map key annotation's parameter type verbatim. Values canonicalize through provider
  wrappers (`Provider<V>`, `Lazy<V>`, `() -> V` all join `V`'s aggregate). Both the contribution
  and accessor sides must use the same canonicalization or they silently never join.
- `@HasMemberInjections` gates supertype traversal for member injection. Metro requires the
  annotation, unlike Dagger.
- Graph supertypes merge their members into the graph. Source supertypes' binding callables come
  from the normal sweep with membership granted via `supertypeIds`; library supertypes' binding
  callables index through their decompiled declarations because the sweep never sees library
  files.
- Diagnostics render with the compiler's `DiagnosticRenderer` and the plain profile, so wording
  matches compilation exactly. Diagnostic construction shared with the compiler lives in
  metro-common's `DiagnosticModelBuilders`.

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

- `MetroResolutionServiceTest`: index construction and editor resolution.
- `MetroIndexDependenciesTest`: dependency keys, contextual keys, seal-facing queries.
- `MetroGraphValidationTest`: seal semantics per diagnostic kind, membership edge cases, caching.
- `MetroToolWindowTreeTest`: tree rows, filtering, refresh identity, a full
  `StructureTreeModel`/`AsyncTreeModel` pass, dumb mode.
- `MetroLineMarkerProviderTest`, `MetroInlayProviderTest`, `MetroImplicitUsageProviderTest`.

Harness gotchas that repeat:

- Tests compute markers on the EDT. Calling `tooltipText` on non-Metro gutters triggers Kotlin's
  inheritor markers into prohibited EDT analysis, so always filter to Metro icons first. Direct
  analysis in production code that can run on the EDT needs `allowAnalysisOnEdt`.
- The daemon caches markers for unchanged files. Re-highlighting after validation requires
  `DaemonCodeAnalyzer.restart()`, same as production.
- Validation results are retained by design, so test classes call
  `MetroGraphValidationService.clearResults()` in `setUp`.

Manual verification runs a sandbox IDE via `./gradlew idea-plugin runLocalIde`, optionally
against a local IDE install (see `idea-plugin/README.md`).
