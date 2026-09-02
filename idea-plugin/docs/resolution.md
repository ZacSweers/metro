# How IDE binding resolution works

`MetroResolutionService` builds binding indexes in the background after source or project changes.
Editor features and actions such as Find Usages and validation query those indexes.

An index holds declarations and lookup tables. A generation groups indexes with the source and
project versions they describe. A session caches queries for one operation against an index.

## 1. Requests enter through one queue

IDE callbacks can arrive on different threads. `ResolutionIngress.submit()` updates counters and
merges requests with matching keys while holding the queue lock. The wakeup channel combines repeated
signals. Requests stay in the queue until the coordinator takes them with `drain()`.

```mermaid
flowchart TD
    Changes["PSI, roots, or settings change"]
    Queries["Index or file-presentation request"]
    Refresh["Refresh action"]
    Ingress["ResolutionIngress.submit<br/>Advance counters and merge matching keys"]
    Wake["Conflated wakeup"]
    Drain["Coordinator drains the queue<br/>Captures requests and their counters"]
    Choose{"Highest-priority runnable work?"}
    Classify["Process pending source, project, and settings changes"]
    Build["Satisfy requests from current indexes<br/>or build a generation"]
    Presentation["Publish completed file presentations<br/>and start queued workers"]

    Changes -->|eventClock + semanticClock| Ingress
    Queries -->|eventClock| Ingress
    Refresh -->|eventClock + refresh ID| Ingress
    Ingress --> Wake --> Drain --> Choose
    Choose -->|1. Pending changes| Classify
    Choose -->|2. Refresh or build requests| Build
    Choose -->|3. File presentation work| Presentation
    Choose -->|None: wait for next wakeup| Wake
    Classify --> Drain
    Build --> Drain
    Presentation --> Drain

    click Ingress "../src/main/kotlin/dev/zacsweers/metro/idea/index/ResolutionIngress.kt"
    click Drain "../src/main/kotlin/dev/zacsweers/metro/idea/index/MetroResolutionService.kt"
    click Build "../src/main/kotlin/dev/zacsweers/metro/idea/index/MetroResolutionService.kt"
    click Presentation "../src/main/kotlin/dev/zacsweers/metro/idea/index/FilePresentationBundle.kt"
```

The coordinator processes source changes, project inputs, settings, manual refresh, ordinary build
requests, and file presentations in that priority order. It checks new arrivals after each work item
and waits when no work can run. Draining moves requests into its pending state. It still needs to
classify the changes to decide what needs rebuilding.

A query for an untracked source file also queues a PSI event to inspect it. This advances the semantic
clock even if the file turns out to contain no Metro declarations.

File presentation workers compute gutter, code vision, and inlay data. They send results through
ingress so the coordinator can check the attempt and index before publishing. At most two presentation
or anchor-update workers run at once. Anchor updates refresh declaration locations while keeping the
existing binding results.

Code: [ResolutionIngress](../src/main/kotlin/dev/zacsweers/metro/idea/index/ResolutionIngress.kt),
[coordinator and publication](../src/main/kotlin/dev/zacsweers/metro/idea/index/MetroResolutionService.kt),
[file presentations](../src/main/kotlin/dev/zacsweers/metro/idea/index/FilePresentationBundle.kt).

### What the clocks and revisions mean

The ingress clocks count accepted requests. They start at zero and keep their values across drains.
Several submissions can merge into one queued event.

| Value | When it changes | Used for |
| --- | --- | --- |
| `eventClock` | Every accepted submission, including merged requests and worker completions. | Numbering submissions and assigning manual-refresh IDs. |
| `semanticClock` | An event may change bindings, such as a PSI, roots, or settings event. | Detecting new input during a build and changes awaiting classification. |
| `latestManualRequestId` | A manual refresh stores its `eventClock` here. Other events leave it unchanged. | Detecting a newer refresh that replaces the one being built. |
| `classifiedSemanticClock` | The coordinator finishes processing source, project, and settings changes through a captured ingress snapshot. | Recording how far classification has progressed. |
| `semanticRevision` | The coordinator invalidates binding data, including when checking a change fails. | Tracking the binding-data version. Irrelevant edits can leave it unchanged. |
| `IndexInputs.roots` / `compilerSettings` | IntelliJ's root or compiler-settings tracker advances. | Checking that the project inputs still match those used by the build. |
| `IndexGenerationToken` | Each build attempt creates an identity shared by its indexes. | Matching indexes and cached presentation results to a build attempt. |

Queries accept a generation as current when all three checks pass:

- Its `semanticRevision` matches `PublishedResolution.latestSemanticRevision`.
- `classifiedSemanticClock` matches ingress's `semanticClock`.
- Its `IndexInputs` match the current project trackers.

In manual mode, the browser shows a stale-data notice while classification is pending or
`latestSemanticRevision` is ahead of `graphBrowserRefreshRevision`. Compiler-setting changes that only
affect generated output can update `IndexInputs` while keeping the same indexes and generation token.

### An edit arrives during a build

Suppose a build starts at `eventClock = 40`, `semanticClock = 8`, and `semanticRevision = 5`:

| Step | Event clock | Semantic clock | Result |
| --- | --- | --- | --- |
| An editor requests a hint. | 41 | 8 | The existing build can continue. |
| A PSI change arrives. | 42 | 9 | The build's captured semantic clock is now behind. |
| Another PSI change arrives before the queue drains. | 43 | 10 | The PSI events merge. Both submissions still count. |
| The build checks its inputs. | 43 | 10 | It requeues its requests without publishing the indexes. |
| The coordinator drains and classifies the edits. | 43 | 10 | It records `classifiedSemanticClock = 10` once the pending changes are processed. |

If either edit affects bindings, the coordinator advances `semanticRevision` and rebuilds the affected
data. If both edits are irrelevant, the revision can stay at `5` and a published index can become
current again. If a canceled read action aborts the build, its requests are requeued. Disposing the
service stops its work and cancels waiting requests.

## 2. Build a complete generation, then publish it

Modules with the same compiler-option fingerprint and library-resolution setting share an index.
Each such group is an index target. `ResolutionSnapshotBuilder` prepares one builder per target inside
the coordinator's smart read action. It reads PSI and the Kotlin Analysis API there, and records module
visibility and source identities for later queries.

```mermaid
flowchart TD
    Work["Coordinator captures pending changes"]

    subgraph Read["Smart read action"]
        Targets["Select index targets by module options"]
        Source["SourceSnapshot<br/>Reuse file shards and rebuild changed files"]
        Aggregate["SourceAggregate<br/>Combine declarations in stable order"]
        Inputs["SourceLibraryInputs<br/>Reuse summary or resolve factories and requests"]
        Libraries["Dependency shards<br/>Reuse or run LibraryIndexPostProcessor"]
        Prepared["PreparedResolutionSnapshot<br/>Private builders and captured module visibility"]
        Targets --> Source --> Aggregate --> Inputs --> Libraries --> Prepared
    end

    subgraph Outside["Outside the read action"]
        Indexes["BindingIndexBuilder.build<br/>Freeze declarations and lookup tables"]
        Check{"Inputs and request still current?"}
        Publish["Publish the complete generation"]
        Indexes --> Check -->|Yes| Publish
    end

    Retry["Requeue requests<br/>and process new changes"]
    Current["current generation"]
    Displayed["presentation generation"]

    Work --> Targets
    Prepared --> Indexes
    Check -->|Inputs changed| Retry --> Work
    Publish --> Current
    Publish -->|Automatic refresh enabled<br/>or manual refresh requested| Displayed

    click Work "../src/main/kotlin/dev/zacsweers/metro/idea/index/MetroResolutionService.kt"
    click Source "../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceSnapshot.kt"
    click Aggregate "../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceAggregate.kt"
    click Inputs "../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceLibraryInputs.kt"
    click Libraries "../src/main/kotlin/dev/zacsweers/metro/idea/index/LibraryIndexPostProcessor.kt"
    click Prepared "../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/PreparedResolutionSnapshot.kt"
    click Indexes "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingIndexBuilder.kt"
    click Publish "../src/main/kotlin/dev/zacsweers/metro/idea/index/MetroResolutionService.kt"
```

A source scan finds files and collects their declarations into `FileShard`s. Incremental builds
replace affected shards, including shards that depend on changed files, and share unchanged data.
Aggregation attaches graph interfaces and associates shared factory inputs with every owning graph.
Source factories are resolved before collecting library requests. When library resolution is disabled,
dependency shards are empty.

`PreparedResolutionSnapshot` holds the mutable builders until `buildIndexes()` freezes them outside the
read action. Before publishing, the coordinator checks for newer events that may change bindings,
changed project inputs, and cancellation. A manual-refresh build also checks for a newer refresh request.
Every target must have a complete index. The coordinator clears the invalidations handled by that build
only after publication.

| Reused data | Owner and lifetime |
| --- | --- |
| Per-file `FileShard` | Cached on the source file with its dependency stamps and force-rebuild tracker. |
| Source snapshot maps and shard order | Shared across incremental snapshots where unchanged. |
| Source/library summary | Kept in `SourceSnapshot` while library inputs and source-module ownership remain unchanged. |
| Dependency shards | `ResolutionSnapshotBuilder` keeps up to eight in an LRU cache and removes entries when roots or module options change. |
| Published indexes | Kept by `MetroResolutionService` and readers using those generations. |

Code: [snapshot builder](../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/ResolutionSnapshotBuilder.kt),
[source snapshot](../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceSnapshot.kt),
[aggregation](../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceAggregate.kt),
[library inputs](../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceLibraryInputs.kt),
[library-input comparisons](../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/SourceLibrarySignatures.kt),
[prepared snapshot](../src/main/kotlin/dev/zacsweers/metro/idea/index/snapshot/PreparedResolutionSnapshot.kt),
[index builder](../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingIndexBuilder.kt).

### Why `current` and `presentation` can differ

Validation, Find Usages, and graph debug export use `current`. The graph browser and editor decorations
use `presentation`. Automatic refresh normally updates both. In manual mode, an action can build a new
`current` generation while `presentation` stays unchanged.

For example, start with both pointing to generation A and disable automatic refresh. Change a provider,
then run validation. Validation builds and queries generation B. The browser and decorations still
show A, with a stale-data notice in the browser. Clicking Refresh builds a generation with the pending
changes and updates both references. Decoration positions can keep moving with their declarations
while the displayed binding data stays at A.

On the EDT, queries for current data return a current cached result or schedule a build and return
empty. Manual presentation and cache-only reads return cached data without scheduling a build.
Background callers can wait for current data. They release any read action before waiting so the
coordinator can acquire its own smart read action.

Code: [request policy](../src/main/kotlin/dev/zacsweers/metro/idea/index/IndexRequestPolicy.kt),
[generation selection and refresh](../src/main/kotlin/dev/zacsweers/metro/idea/index/MetroResolutionService.kt).

## 3. Query an index, then resolve a graph when needed

`BindingIndex` contains immutable declarations, lookup tables, and module views. Each operation uses
a `BindingResolutionSession` to cache graph contexts, contribution selections, and query plans. A
session has one caller at a time and is discarded when the operation finishes. Separate operations
can read the same index concurrently.

```mermaid
flowchart TD
    Index["Immutable BindingIndex"]
    Session["BindingResolutionSession<br/>Temporary caches for one operation"]
    Path["GraphContext<br/>Concrete graph and parent path"]
    Visibility["Captured module view<br/>Visible declarations at the root or call site"]
    Query["GraphQueryContext<br/>Graph path, module view, and containers"]
    Plans["Session-cached contribution selections<br/>and query plans"]
    Editor["Editor relationships<br/>Navigation, Find Usages, and presentations"]
    KA["IDE validation<br/>KaBindingGraph + KaBindingLookup"]
    IR["Compiler IR<br/>IrBindingGraph + BindingLookup"]
    Shared["metro-common<br/>Dependency traversal, cycles, topology,<br/>and shared validation rules"]
    IDEOutput["IDE diagnostics and graph results"]
    CompilerOutput["Compiler diagnostics and code generation"]

    Index --> Session
    Session --> Query
    Path --> Query
    Visibility --> Query
    Query --> Plans
    Plans --> Editor
    Plans --> KA
    KA --> Shared
    IR --> Shared
    Shared -->|IDE adapter| IDEOutput
    Shared -->|IR adapter| CompilerOutput

    click Index "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingIndex.kt"
    click Session "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingResolutionSession.kt"
    click Path "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingModel.kt"
    click Query "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingModel.kt"
    click Plans "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingIndex.kt"
    click Visibility "../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingIndexBuilder.kt"
    click KA "../src/main/kotlin/dev/zacsweers/metro/idea/graph/KaBindingGraph.kt"
    click IR "../../compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/graph/IrBindingGraph.kt"
    click Shared "../../metro-common/src/main/kotlin/dev/zacsweers/metro/compiler/graph/BindingGraph.kt"
```

The graph path determines the scopes, contributions, exclusions, containers, and parent bindings used
by the query. The module view determines which declarations are visible from the root graph or dynamic
call site. An extension can have several parent paths, so a query needs the path as well as the
declaration. When the index has no graphs, consumer queries fall back to bindings visible from the
consumer's module.

Graph-specific editor queries filter out bindings with incompatible scopes. Validation keeps them so it
can report the conflict. A validation run can reuse one session per index across several graphs. For
each graph, `KaBindingGraph` calls the shared graph code to follow dependencies and validate them.
`KaBindingLookup` supplies bindings along the way.

The IDE and compiler use their own types, binding lookups, and diagnostic reporting. Both call
`MutableBindingGraph.seal()` and the validation rules in `metro-common`. The IDE also uses shared
contribution rules such as `computeMergePlan`. Request scheduling, captured module visibility,
navigation pointers, and editor updates live in `idea-plugin`.

Code: [binding index](../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingIndex.kt),
[resolution session](../src/main/kotlin/dev/zacsweers/metro/idea/model/BindingResolutionSession.kt),
[IDE graph adapter](../src/main/kotlin/dev/zacsweers/metro/idea/graph/KaBindingGraph.kt),
[IDE lookup adapter](../src/main/kotlin/dev/zacsweers/metro/idea/graph/KaBindingLookup.kt),
[validation runs](../src/main/kotlin/dev/zacsweers/metro/idea/graph/MetroGraphValidationService.kt),
[compiler graph adapter](../../compiler/src/main/kotlin/dev/zacsweers/metro/compiler/ir/graph/IrBindingGraph.kt),
[shared graph traversal](../../metro-common/src/main/kotlin/dev/zacsweers/metro/compiler/graph/BindingGraph.kt),
[shared validation](../../metro-common/src/main/kotlin/dev/zacsweers/metro/compiler/graph/BindingGraphValidator.kt).
