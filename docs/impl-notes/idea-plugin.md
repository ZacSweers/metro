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

Contribution priority selection uses the same inline helper in IR and the IDE. Anvil's
`rank` is treated as an alternative priority argument only when its interop is enabled.

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

`metro-common/src/test/.../graph/GraphWorkload.kt` also generates a reproducible mixed workload
from a fixed seed, so unit tests and future graph tooling can reuse the same fixture. It includes
long dependency chains, shared high-fan-in services, wide set/map aggregates, qualified keys,
multiple roots, legal provider/lazy cycles, constructor bindings discovered on demand, and
unreachable bindings. Separate JMH cases compare complete graph sealing, unused-binding pruning,
and lazy population; another measures suspend propagation through the same mixed shape. Binding
kind and scope labels are fixture metadata: the shared graph core does not perform compiler or
IDE scope membership checks.

## Layers

Data flows in one direction:

```
module options
      |
      v
coordinator (changes, requests, background builds)
      |
      v
immutable BindingIndex generations
      |
      +--> presentation generation --> file bundles --> editor features
      |                         |
      |                         '--> graph browser
      |
      '--> current generation --> validation (graph seal)
                 |
                 v
            retained results --> tool window and validation badges
```

### Options and settings

- `MetroIdeProjectService` / `metroIdeState()`: per-module `MetroOptions` parsed from the Kotlin
  facet's compiler plugin args, plus an options fingerprint for cache keying. A module is active
  only when Metro compiler plugin options are present and the plugin is enabled. Kotlin modules
  without Metro stay inactive even though the compiler option itself defaults to enabled.
  UI callbacks read cached options and schedule missing options in a background read. Requests for
  the same module share that work, and waiting callers are notified when the options are available.
  Contribution-provider mode follows the compiler: contributed implementation bindings stay
  hidden unless `@ExposeImplBinding` or another compiler exemption keeps them available.
- `MetroSettings`: project-level toggles for editor navigation, automatic refresh, library resolution,
  and inlays.
  Hiding editor navigation does not disable graph browsing or validation. Library resolution
  applies to both editor features and graph tools.

### Index

`index/MetroResolutionService.kt` owns index requests, invalidation, background builds, and
publication. PSI and project listeners enqueue changes. One coordinator classifies those changes,
updates dependency tracking, and decides which work to start. Readers use an immutable published
snapshot. A cache hit checks captured revisions without rescanning modules or resolving PSI.

Index generations use semantic compiler options, so module-specific report and trace paths do not
create duplicate indexes. The first build finds candidate files through annotation stub indexes and
targeted import searches for annotation aliases. Later edits update changed files and dependent
shards, including inherited declarations and qualifier, scope, or map-key annotation defaults.
Unrelated shards and dependency state are reused. Changes to otherwise untracked type aliases or
constants, including directory moves, conservatively rebuild all source shards. Project roots and
compiler settings are checked once per queued batch. Directory changes add newly relevant files as a
batch.

The current generation serves validation, Find Usages, and debug export. The presentation generation
serves editor decorations and the graph browser. Automatic refresh advances both. Manual refresh mode
retains the presentation generation while explicit operations can build current data. **Refresh** or
reenabling automatic refresh brings editor presentation up to date, including after an explicit query
refreshed current data in manual mode. A generation records the inputs it used, so a late worker
cannot publish over newer work.

Analysis API work runs in cancellable background smart reads. Graph query results and lookup tables
are built from captured data outside read actions where possible. Explicit callers wait for a missing
generation outside their read action, then retry against the published result. Presentation requests
return available data and schedule missing work. Duplicate requests share a build. Presentation
workers have a concurrency limit, and completion or cancellation releases a slot before queued work
starts.

Compiled-library results have a separate cache keyed by classpath, graph scopes, requested types,
binding dependencies, and their use-site modules. Equivalent per-file inputs reuse the same source
summary, so unrelated edits do not repeat factory visibility checks. Inherited graph requests retain
their owning graph declaration and module.

- `index/FileShardBuilder.kt`: builds one file's shard. Files are found through
  `KotlinAnnotationsIndex` by annotation short names or import aliases, then resolved with the
  Analysis API inside `analyze {}` blocks. Handles graphs (including supertype members and
  providers specialized to the graph's concrete type arguments and declaration identity), binding
  callables (companion members attribute to the enclosing container), graph `@Multibinds`
  accessors, inject classes, top-level function injection, contributions, assisted factories, and
  binding containers.
- `index/BindingExtraction.kt`: symbol-to-model extraction shared by source and library paths.
  Computes type keys, dependency keys, map key info, contribution priorities, and multibinding ids.
  Generated contribution providers depend only on constructor parameters, matching the actual
  compiler-generated provider; an exposed injectable implementation retains its injected members.
- `index/SourceClassBindingPostProcessor.kt` and `index/LibraryIndexPostProcessor.kt`: resolve
  concrete constructor, object, and assisted-factory requests across source and libraries. They
  share class extraction and a bounded expansion state. Declaration dependencies include source
  files reached through library requests and owners waiting for unresolved source types.
- `index/LibraryContributionScanner.kt`: reads generated hint functions and their binding or graph
  interface surfaces. Generated aliases inherit their origin's contribution priority. Public hints
  need classpath visibility; nonpublic hints use Kotlin's friend-module rules. Source and binary
  graph members share the extractors in `index/graph/`.
- `index/LibraryGraphDiscovery.kt`: follows referenced binary child graphs and scans newly reached
  contribution scopes. Requests retain their source use-site modules. Cached child metadata is
  combined with current source members and factory-input owners before graph indexing.

### Model

Everything the index stores is session-free. Nothing may retain a `KaSession`, `KaType`, or
`KaSymbol`. Types become `KaTypeSnapshot` (interned render strings plus `ClassId` and recursive
type arguments), annotations become `KaAnnotationSnapshot` (constructor-ordered resolved
arguments, including declared defaults), and declarations are held as `SmartPsiElementPointer`s.

- `model/BindingModel.kt`: `KaGraphDeclaration`, `ConsumerEntry`, and friends.
- `model/KaBinding.kt`: the binding model. Mirrors the compiler's `IrBinding` + sealed subtypes.
- `model/KaContextualTypeKey.kt` / `model/KaTypeSnapshot.kt`: key model. Key equality is
  string-render equality, so both sides of any match must canonicalize the same way.
- `model/BindingIndex.kt`: the immutable query surface. `BindingIndexBuilder` captures its inputs,
  and each published index keeps its `BindingIndexLookups` key and file tables for queries to reuse.
  `BindingResolutionSession` owns query caches for one operation, so a file presentation build or
  validation traversal reuses context and consumer resolution without sharing mutable query state
  with concurrent operations. `contextsFor(graph)` merges the extension parent chain
  into a `GraphContext` (scopes, containers, includes, excludes, supertype ids).
  - Membership filtering applies graph/module visibility, scope matching, declaration-specific
    containers, member-injection ownership, exclusions, explicit replacements, and contribution
    priorities. Exclusions happen before replacements, and priority selection runs last within
    each graph's own scopes. Higher-priority bindings replace only matching qualified bindings;
    map contributions additionally match their map-key values. Replacing a contribution never
    removes its separate injectable concrete type.
    Graph-private bindings, including optional bindings, stay in their owning graph, and assisted
    targets are not presented as ordinary injectable bindings.

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
  classification. Shared structural validation checks duplicate map keys, empty multibindings,
  scopes, and assisted targets without allocating compiler-side metadata wrappers for each
  binding. Lookup state is cleared after sealing.
- `graph/KaBindingLookup.kt`: pull-based binding resolution. Only keys reachable from roots are
  ever looked up, which is what keeps validation proportional to the graph rather than the
  project. Multibindings synthesize per-element keys with a synthetic `@MultibindingElement`
  qualifier, matching the compiler's key swap. Parent-owned scoped bindings, including
  multibinding contributions from included binding containers, resolve through the owning graph
  and its module options.
- `graph/MetroGraphValidationService.kt`: captures immutable validation inputs in cancellable smart
  reads, then seals outside read access. `ValidationInputCapture` owns one resolution session per
  index throughout a traversal. `ValidationSourceSnapshot` supplies captured source details.
  Each attempt collects results locally. Asynchronous requests publish after computation when the request is
  still current. Cancellation and replaced requests leave retained results unchanged. Publication
  also checks whether results were cleared and which run produced each entry, so an older run
  cannot overwrite newer results.
  Results are retained by graph declaration and parent path in a bounded cache. They remain visible
  with a stale flag after index invalidation. `validateWithExtensions` seals extensions before their
  parents, mirroring the compiler's traversal. Progress and completion callbacks update the EDT.
- `graph/KaSuspendBindingValidator.kt`: converts Analysis API bindings and graph requests to shared
  suspend-validation inputs, then turns shared issues and witness paths into navigable IDEA
  diagnostics. Witness provenance is built only when a diagnostic needs it and reused across
  requests. The adapter contains no suspend validation policy. Suspend parity fixtures cover valid
  transitive/deferred paths and representative failures.

Explicit actions and opt-in pinned-graph validation submit requests to the same service. Index builds
and highlighting passes perform no sealing. The pinned-graph service owns its debounce and running
job, cancels superseded requests, and pauses while indexing or automatic graph refresh is disabled.

### Editor features

`index/FilePresentationBundle.kt` holds one file's resolved consumers, reverse usages, graph
contributions, and inlay choices. Providers request a cached bundle and return promptly while missing
data is built. Reverse usages retain both positive and negative answers for each consumer's graph
contexts, so pinning a graph excludes consumers that resolve to a different binding there.

Binding results and source anchors have separate lifetimes. A file edit invalidates the bundle's
offset map. A background read rebuilds that map from smart pointers and checks declaration names,
kinds, and enclosing declarations. Changed declaration identities and ambiguous matches receive no
decoration. This keeps manual refresh data usable as declarations move, and each editor lookup
remains a direct map lookup after the file's current modification stamp is checked.

- `index/MetroLineMarkerProvider.kt`: binding, consumer, injector, graph contributions, and
  validate markers. Navigation targets are captured as smart pointers during background collection.
  `MetroNavigationService` restores them in a background read and delivers only the latest request
  for each editor or tool window while its owner remains open. The validate marker badges the last
  validation outcome and runs validation through the tool window.
- `index/MetroCodeVisionProvider.kt`: consumer and contribution counts. Zero counts are omitted.
- `index/MetroInjectedImplementationInlayProvider.kt`: resolved-implementation and
  multibinding-count inlays, plus `assisted` hints for implicitly assisted parameters.
- `MetroImplicitUsageProvider.kt` and `MetroUnusedDeclarationInspectionSuppressor.kt`: implicit
  usage and inspection suppression driven by the same options. EDT checks use cached answers from
  resolved annotation identities. A miss schedules one background read for the whole file. PSI,
  root, and compiler setting changes invalidate those answers.

`MetroDaemonRestartService` batches highlighting refresh requests and waits for an active daemon pass
to finish. Index and presentation publication, pin changes, and validation can request a refresh
without repeatedly interrupting the current highlighting pass.

### Tool window

- `toolwindow/MetroTreeStructure.kt`: an `AbstractTreeStructure` over plain value nodes. Children
  compute on demand (a graph's bindings are only queried when expanded), display data is
  precomputed under the model's read action so rendering never touches PSI, and node identity is
  content-aware because `AsyncTreeModel` reuses equal nodes and would otherwise serve stale
  children. Categories: Scoped, Unscoped, Multibindings (grouped by aggregate id), Contributed,
  and Unused (authored bindings nothing requested, unioned with cached extension seals). Returns
  no children in dumb mode.
- `toolwindow/MetroToolWindowPanel.kt`: `StructureTreeModel` + `AsyncTreeModel`, search filter,
  explicit Load/refresh controls, and post-validation selection of the result node. The panel loads
  graphs only after requested; `toolwindow/IndexBuildStatusPanel.kt` reports background progress
  and waits for IDE indexing. The tree refreshes when source or project configuration changes,
  background indexing finishes, or the IDE leaves dumb mode. A validation requested before its
  graph is indexed remains pending until that exact graph becomes available. Closing the panel
  prevents outstanding callbacks from touching its tree.
- `toolwindow/ValidateMetroGraphAction.kt`: editor action plus the shared
  `openAndValidate(project, classId, file)` entry the gutter uses.
- `toolwindow/MetroGraphDebugExporter.kt` and `toolwindow/ExportGraphDebugInfoAction.kt`: export
  only the selected graph's exact parent path from the overflow menu. Reports are built in a
  background smart read action, reuse cached validation without resealing, and are written locally
  to the IDE log directory. Source bodies, absolute paths, and annotation literal values are
  redacted; no report is uploaded, although project and type identifiers remain sensitive.

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
  library supertype declarations are tracked as shard dependencies. A specialized inherited
  provider replaces its raw declaration only in the graph that owns that specialization.
- Generic assisted factories use their concrete requested type for inherited factory methods and
  assisted-target dependencies. Source declarations and compiled libraries follow the same rule.
- Equal-length suspend diagnostic paths follow the dependency's declaration order without
  rebuilding the shared witness search for each diagnostic.
- Diagnostics use the compiler's `DiagnosticRenderer` and plain profile. Shared diagnostic builders live in `metro-common`; differential fixtures compare frontend-specific diagnostics by ID and normalized one-line title.

## Build wiring

The plugin is a composite build that substitutes `dev.zacsweers.metro:metro-common` and shades it
along with androidx.collection and androidx.tracing (both `compileOnly` in metro-common). The
kotlinx-coroutines transitively pulled by androidx.tracing is excluded at the configuration
level: the IDE ships a patched coroutines build, and shadowing it breaks at runtime with
confusing `NoSuchMethodError`s. Never add a coroutines dependency to the plugin.

## Testing

Most tests use `BasePlatformTestCase`, with helpers in `ktTestUtils.kt`: `configureMetroFile`
(default package and Metro star import), `setMetroOptions`, `addMetroRuntimeLibrary`, and
`withMetroLibFixtureLibrary` (a jar compiled from `src/test/data/libFixtures/` for binary
resolution tests). `MetroMultiModuleResolutionTest` uses a dedicated multi-module fixture instead.
`awaitIndex` waits for the current generation outside the fixture's EDT read action.
`awaitMetroPresentation` waits for both the file's binding results and its current source anchors.

`setMetroOptions()` always supplies `enabled=true` unless a test explicitly overrides it.
`clearMetroOptions()` represents a module where the Metro compiler plugin is not configured.

- `MetroResolutionServiceTest`: index construction, request coalescing, cancellation, generation
  reuse, manual refresh, and editor resolution.
- `MetroFilePresentationTest`: pinned reverse usages, source edits, anchor rebuild coalescing,
  stale completion, and disposal.
- `MetroMultiModuleResolutionTest`: graph visibility, inherited bindings, and ownership across
  source modules.
- `MetroIndexDependenciesTest`: dependency keys, contextual keys, seal-facing queries.
- `MetroGraphValidationTest`: seal semantics per diagnostic kind, membership edge cases, caching,
  cancellation, and publication ordering.
- `MetroSuspendGraphValidationTest`: suspend propagation, request boundaries, member injection, assisted factories, multibindings, and runtime requirements.
- `MetroGraphValidationParityTest`: live IDEA validation compared with checked-in compiler graph reports, including suspend success and failure cases.
- `MetroToolWindowTreeTest`: tree rows, filtering, refresh identity, a full
  `StructureTreeModel`/`AsyncTreeModel` pass, dumb mode.
- `MetroLineMarkerProviderTest`, `MetroInlayProviderTest`, `MetroImplicitUsageProviderTest`.

Harness gotchas that repeat:

- Calling `tooltipText` on non-Metro gutters triggers Kotlin's
  inheritor markers into prohibited EDT analysis, so always filter to Metro icons first. The
  index and file presentation workers remain asynchronous in tests. Wait through the fixture
  helpers before checking decorations.
- The daemon caches markers for unchanged files. Re-highlighting after validation requires
  `DaemonCodeAnalyzer.restart()`, same as production.
- Validation results are retained by design, so test classes call
  `MetroGraphValidationService.clearResults()` in `setUp`.

Manual verification runs a sandbox IDE via `./gradlew -p idea-plugin runLocalIde`, optionally
against a local IDE install (see `idea-plugin/README.md`).
