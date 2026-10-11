# Graph Analysis & Visualization

Metro provides Gradle tasks for analyzing and visualizing dependency graphs.

## Setup

Graph analysis requires setting the `reportsDestination` property in your Metro configuration:

!!! warning
    Leave this disabled by default. Generating reports can be verbose and expensive. The Kotlin Gradle Plugin does not include `reportsDestination` as a task input. You may need to recompile with `--rerun` after enabling it.

```kotlin
metro {
  reportsDestination.set(layout.buildDirectory.dir("reports/metro"))
}
```

This enables the compiler to export graph metadata during compilation.

## Binding explanations

Graph metadata includes `bindingExplanations`. These use the same decision model and reason codes as the IDE's **Why this Metro binding?** action. The aggregated metadata and `analysis.json` include these records. Reports also cover generated child and dynamic graphs.

Each explanation identifies the graph, the requested key when one exists, and the candidates the compiler considered. Reasons such as `selected_explicit`, `selected_parent`, `excluded`, and `replaced` appear as structured fields. Related declarations identify the graph or contribution responsible for a removal when that information is available.

The `phase` identifies binding registration, dependency lookup, or candidate filtering. A filtering record can describe an excluded contribution before anything requests it. Lookup records include only candidates reached by the compiler's normal lookup. Fallbacks that weren't evaluated are absent. The IDE can show additional alternatives from its project index.

Setting `reportsDestination` enables explanation collection without changing binding selection. JSON reports are written during code generation. A compilation that stops earlier may have no report for the failing graph.

## Provider inlining counts

Each graph's JSON report includes `stats.optimizations.providerInlines`. This counts inline value expressions emitted for that graph. A provider used at two generated access sites can count twice. These counts don't measure runtime calls.

`providerInlineFallbacks` records known inline candidates that use another code path:

| Field              | Meaning                                                                   |
|--------------------|---------------------------------------------------------------------------|
| `deferredAccess`   | Provider or lazy access needs to keep value evaluation deferred.          |
| `unavailableValue` | The compiler couldn't materialize the value in the consuming compilation. |

Providers without inline metadata aren't included in the fallback counts. Their bodies may be ineligible for inlining, or inlining may be disabled.

The counters are available in per-graph and aggregated metadata.

## Available Tasks

!!! warning
    These tasks are intended for analysis and visualization. They aren't intended for continuous validation because of the `reportsDestination` limitations described above.

The global `generateMetroGraphMetadata`, `analyzeMetroGraph`, and `generateMetroGraphHtml` tasks run their corresponding report tasks for every Kotlin compilation in the project.

To target one compilation, use a scoped task name. It includes the capitalized target and compilation names. A blank target name is omitted. Running a scoped report task compiles its selected compilation and the dependencies that compilation needs.

| Compilation                          | Metadata task                             | Output directory                     |
|--------------------------------------|-------------------------------------------|--------------------------------------|
| Android `internalDebug`              | `generateInternalDebugMetroGraphMetadata` | `build/reports/metro/internalDebug/` |
| JVM `main`                           | `generateMainMetroGraphMetadata`          | `build/reports/metro/main/`          |
| KMP `jvm` target, `main` compilation | `generateJvmMainMetroGraphMetadata`       | `build/reports/metro/jvm/main/`      |

Outputs use `build/reports/metro/{target}/{compilation}/`. The target directory is omitted when the target has no name. Each compilation has its own metadata, analysis, and HTML reports.

### `generate<Target><Compilation>MetroGraphMetadata`

Combines the compiler's per-graph metadata from the selected compilation into one JSON file.

```bash
./gradlew :app:generateInternalDebugMetroGraphMetadata
```

**Output:** `build/reports/metro/internalDebug/graphMetadata.json`

The compiler writes individual reports under `{reportsDestination}/{target}/{compilation}/graph-metadata/`. The analysis and HTML tasks depend on the metadata task for the same compilation. You usually don't need to run it directly.

### `analyze<Target><Compilation>MetroGraph`

Combines graph metadata from the selected compilation into an analysis report.

```bash
./gradlew :app:analyzeInternalDebugMetroGraph
```

**Output:** `build/reports/metro/internalDebug/analysis.json`

This task analyzes the combined graph metadata. You can use its JSON output in other tools.

### `generate<Target><Compilation>MetroGraphHtml`

Generates interactive HTML visualizations of your dependency graphs. Each file includes the graph data, styles, and scripts.

```bash
./gradlew :app:generateInternalDebugMetroGraphHtml
```

**Output:** `build/reports/metro/internalDebug/html/` containing:

- `index.html` - Lists all graphs
- `{graph-name}.html` - Interactive visualization for each graph

Open the HTML files directly in a browser. They work offline and have no external dependencies.

### Updating Existing Commands

Existing commands using the global task names remain valid and run all compilations. Use the scoped names above when you want to target one compilation.

Update report readers to use the compilation's output directory. For example, `build/reports/metro/graphMetadata.json` moves to `build/reports/metro/internalDebug/graphMetadata.json` for Android `internalDebug`. Global tasks also write separate reports for each compilation.

The global names now refer to aggregate tasks. Move typed task configuration to a scoped task, such as `tasks.named<GenerateGraphHtmlTask>("generateMainMetroGraphHtml")`. To configure HTML reports for all compilations, use `tasks.withType<GenerateGraphHtmlTask>().configureEach { ... }`. The same applies to `GenerateGraphMetadataTask` and `AnalyzeGraphTask`.

## Open Reports in the Browser

[Open the graph viewer](graph-viewer/index.html) to browse reports without generating HTML first. Drop compiler `graph-*.json` files or `graphMetadata.json` onto the page. You can also choose files with the file picker or try the sample graph.

Files stay in your browser. The viewer doesn't upload or save them. Choose several reports from the same compilation to see extensions, their parents, and included graph dependencies together. Missing related reports are listed above the graph. **Add reports** adds files to the current import. **Replace reports** starts a new import.

Include `analysis.json` to see centrality, dominator counts, and the longest chain. Search, layouts, roots, and connections work without analysis. The viewer checks graph names and binding keys before accepting analysis. Import `graphMetadata.json` and `analysis.json` from the same compilation's output directory. Use **Replace reports** when switching variants or targets.

The viewer accepts Metro's current unversioned JSON reports. Use the docs version that matches the Metro version that produced your reports. Older reports may lack accessor names or ownership information. Conflicting reports for the same graph are rejected.

## Browsing a Graph

The viewer opens with a package overview. It groups related packages by namespace and shows the number of visible bindings in each group. Lines show dependencies between groups. The overview includes related graphs available in that HTML report, including extensions and identified graph dependencies.

An extension report with ancestor metadata opens in **Full graph** and fits the extension and its ancestors to the view.

Select a group to browse its bindings and their immediate connections. You can also select an individual package in the browser panel or search for a type. Bindings without package information appear under **Unassigned package**.

### Find a Binding

Search checks the full type key, display name, binding kind, scope, origin, and declaration. Choose a package to search within it or **All packages** to search the whole graph. The results also include bindings hidden by the map's display options.

Use **Sort by** to sort bindings by their dependency counts, consumer counts, centrality, or dominator counts. Select a result to inspect it and find it on the map. Use **Show more results** to load more bindings.

Labels use simple class names such as `Service`. Nested classes include their parent name, such as `Presenter.Factory`. Package names appear when multiple classes have the same name. Qualifiers or source locations distinguish bindings of the same type.

### Explore Connections

The selected binding's name appears beside **Connections** and **Route from root** above the map. Both actions apply to that binding and remain available when the map is expanded.

**Connections** opens a view of the selected binding's connections. Choose **Dependencies**, **Consumers**, or **Both directions**. Set **Initial depth** to one to three levels. Click a binding to expand one level of connections in the chosen direction. Click it again to collapse that branch. The plus or minus below the binding shows whether it's expanded. Other expanded branches and shared dependencies stay visible.

Selecting an already visible binding in the browser or inspector keeps the current view and expanded branches. Use **Connections** to start a new view from the selected binding. Use **Back** to return to the previous view.

Connections initially shows up to 120 bindings. It also includes any bindings needed to complete the route back to a root. The status bar shows how many bindings are hidden and how many connections continue outside the view. Use **Show more** to see more bindings. Existing bindings stay in place as you expand the view. Clearing the selection keeps the expanded branches open.

### Trace a Route

Selecting or hovering over an accessor or injector highlights its visible direct and transitive dependencies. This includes connections through hidden synthetic bindings. Selecting an ordinary binding highlights its dependency paths back to a root. Selecting a graph input highlights its immediate visible consumers. Connection filters apply to these highlights.

Choose **Route from root** to show a shortest directed route from a root to the selected binding. Routes can include deferred dependencies such as `Provider` and `Lazy`. Graph inputs have no route action. Use **Connections** to inspect their consumers.

Route view shows every binding and connection along the route, including synthetic bindings and default values. Graph outlines show which bindings are available to each graph. **Automatic** direction reads downward from the selected dependency to its accessor or injector.

Selecting another visible binding inspects it within the same route. Clearing the selection keeps the route open. **Back** returns to the previous view. The route action is disabled when no route is available. The inspector explains why.

Graph extensions can inherit multibinding root requests. These appear as **Inherited root** with the original accessor's declaring type and name. Their connections lead to contributions resolved in the extension. The inspector shows the ancestor graph and source declaration. These requests don't add public accessors to the extension.

**Longest chain** highlights a deepest dependency path in the full, radial, or circular graph. The highlight stays active when you switch layouts or change Direction. **Isolate chain** shows the path on its own with its named root. **Show in graph** returns to the previous layout. Selecting or deselecting a binding keeps the chain open. Press Escape with no selected binding to clear the highlight.

Numbered bindings belong to the measured chain. The count includes aliases and graph inputs. Deferred dependencies are excluded. The binding and edge counts measure dependency depth. They don't measure initialization time.

### Inspect a Binding

The inspector shows the binding key, owning graph, kind, scope, declaration, and origin when available. It omits internal multibinding annotation markers from the displayed text. **Copy key** copies the complete original key with its qualifiers and generic arguments.

Click a dependency or consumer to inspect it. Hover over a key or connection row to see its full type information. **Declared dependency keys** shows the compiler's dependency records with wrapper types and default value information. Assisted parameters and multibinding contributions appear when they're recorded in the metadata.

Following an inspector connection with the keyboard moves focus to the new binding's heading. Tab moves through its actions and connections. Arrow keys pan when the map has focus. The plus and minus keys zoom. Browser shortcuts such as Cmd/Ctrl with plus or minus work as usual.

The **Analysis** section shows consumer and dependency counts, centrality, and dominator count. These counts can differ from the visible connections because the viewer also shows roots, assisted targets, and default values.

**Compiler decisions** shows the binding explanations recorded during compilation. These include candidate outcomes, reasons, declarations, and source locations when available. The compiler records the candidates it considered. Other possible bindings in the project may be absent. Older reports may have no explanation records for a key.

Clear the selection to inspect compiler counters and configuration for the whole graph. You can click blank space outside the inspector and help panel, use **Clear selected binding** (×), or press Escape outside an input. Clearing the selection keeps the current view and its pan and zoom. Reading inspector or help text and dragging the map leave the selection unchanged.

## Reading the Map

### Bindings and Connections

An outline surrounds the bindings available to each graph. An extension's outline includes its own bindings and its ancestors' boundaries. Padding keeps the outline clear of the bindings and lines inside it. A faint fill and dot grid mark the enclosed area. Overlapping regions keep the same brightness. Graph names sit near their own binding groups and stay clear of the outline as you zoom.

Hover over a graph name to highlight its boundary. This also reveals boundaries omitted from **Circular** while you hover.

Accessors and injectors are roots. Graph inputs appear as squares. Roots and graph inputs sit on the boundary in Full graph, Radial, Connections, and routes. Short lines connect them to the bindings inside. Inputs enter on the left by default. Accessors and injectors sit on the right. Routes read from top to bottom.

Ordinary bindings appear as circles. Scoped bindings have a white border. The graph instance appears as a separate binding when other bindings depend on it. Package groups in **Overview** show a binding count and use group colors.

In binding views, colors identify binding kinds:

| Color       | Binding kind                                         |
|-------------|------------------------------------------------------|
| Blue        | Constructor-injected bindings and graph dependencies |
| Yellow      | `@Provides` bindings and default values              |
| Gray        | Aliases                                              |
| Teal        | Bound instances, objects, and custom wrappers        |
| Purple      | Multibindings                                        |
| Orange      | Graph extensions                                     |
| Red         | Assisted factories and assisted-inject targets       |
| Light green | Members injection                                    |

Arrows and moving dots point from a dependency to its consumers. Dotted lines identify aliases. Dashed lines identify deferred dependencies and other marked connections. The inspector shows each connection's type and wrapper information.

Sibling extensions overlap around their shared ancestors. Each sibling's own bindings stay outside the other sibling's outline. Shared bindings appear once in their owning graph. Consumers in extensions connect directly to them. Unscoped bindings resolved separately in different graphs appear in each graph.

Included graphs appear separately when the report contains enough information to identify them. They connect through `@Includes` and their named roots. Select an extension or graph dependency in the inspector to fit it into view. Opening an extension report shows that extension and its available ancestors.

Accessors show their property or function name above the requested type. Functions and injectors include parentheses. Reports without recorded names use the requested type as the label. **Graph** shows the graph boundary at a readable zoom. **Roots** shows the roots and their immediate dependencies. Select a root to inspect its requested type and connections.

### Display Options

**Extensions** is on by default. Turn it off beside **Roots** to hide extension-owned bindings and boundaries in every layout. The map refits automatically. Selected bindings stay selected if they're still visible. An extension report keeps its own graph and ancestors visible. Graph dependencies remain visible.

Hiding extensions returns to **Full graph** if it hides the starting binding in Connections or removes a required part of the current route or chain. Opening a hidden extension or one of its bindings turns extensions back on. **Longest chain** also shows extensions when the chain needs them.

**Show synthetic bindings** and **Show default values** control which bindings appear on the map. Lines through hidden synthetic bindings connect their visible neighbors. Expand a connection's **Via** details in the inspector to see those hidden bindings. The selected binding and graph root stay visible. The **Connections** display filter limits the map to direct dependencies, deferred dependencies, or graph accessors.

**Direction** sets the map's reading direction. **Automatic** uses left to right for graphs and top to bottom for routes. You can also choose **Left → right**, **Right → left**, **Top → bottom**, or **Bottom → top**. The separate **Follow** control in Connections chooses which dependencies or consumers to show.

Changing Direction or switching between **Overview**, **Full graph**, **Radial**, and **Circular** fits the new layout to the available space. Your selection and expanded-map state stay the same. Changing Direction also keeps expanded branches open.

**Full graph** shows all bindings allowed by the display options. It spreads crowded groups across multiple columns. Each graph's bindings stay together. Extensions sit around their parent graph with space for their outlines and names. Labels appear as you zoom in, hover, or select a binding.

**Circular** places each graph's bindings, roots, and inputs on a circle. Graphs without ancestors show their name and omit the outline, fill, and grid. An extension's outline includes its circle and its ancestors' circles. Roots and inputs stay on the circle with direct connections to other bindings.

**Radial** arranges bindings in rings by dependency depth. The rings progress inward from the roots. Roots and graph inputs sit on the boundary. Both layouts use the same display options and inspector.

### Navigation and Motion

- Use **Expand map** or press `F` to fill the window with the map and its controls. **Exit expanded map** or `F` restores the panels. Both actions fit the current view to the available space. Your selection and filters stay the same.
- Drag the map to pan.
- Click blank space outside the inspector and help panel to clear the selection. **Clear selected binding** (×) does the same. The current view stays open.
- Scroll to zoom or use the zoom buttons.
- Use **Fit map to view** or double-click the map to fit the entire current view. This includes long routes.
- Press `/` to focus search. Use the arrow keys and Enter to navigate results.
- Press Escape outside an input to clear the selection. Press it again to clear any chain highlight. Escape exits the expanded map once both are clear.
- Moving between views animates the layout and fades bindings in and out. Dragging or zooming interrupts the camera movement.
- Use **Pause motion** to stop the moving dots and turn off transitions. Motion starts paused when your system requests reduced motion.

The map uses a fixed layout and draws only the visible area. You can start browsing immediately. Package groups, limited Connections views, and hidden labels help keep large graphs readable.

## Example Workflow

```bash
# Generate visualizations for internalDebug
./gradlew :app:generateInternalDebugMetroGraphHtml

# Open in browser
open app/build/reports/metro/internalDebug/html/index.html
```

1. Open a graph and use the package overview to find the area you want to inspect.
2. Search for a type or sort by **Most depended on** to find shared dependencies.
3. Select a binding and explore its dependencies or consumers.
4. Use **Route from root** beside the selected binding's name to see how a root reaches it.
5. Inspect its compiler decisions or use **Longest chain** to investigate dependency depth.

Each graph's HTML file contains its data and viewer resources. You can open it directly from disk or share it with someone who needs to inspect the same report.

## Analysis Metrics

The `analyze<Target><Compilation>MetroGraph` task computes the following metrics for each graph.

### Fan-In and Fan-Out

| Metric      | Meaning                                          |
|-------------|--------------------------------------------------|
| **Fan-In**  | Number of other bindings that depend on this one |
| **Fan-Out** | Number of dependencies this binding requires     |

High fan-in is common for shared utilities and services. Changes to these bindings can affect many consumers. Stable APIs and test coverage help limit that impact.

High fan-out can mean a binding has too many responsibilities. Consider whether some of its work belongs in separate classes. Bindings with both high fan-in and high fan-out deserve particular attention because they're widely used and have many dependencies of their own.

### Betweenness Centrality

Betweenness centrality measures how often a binding lies on the shortest path between other bindings. A high score means many dependency paths pass through it.

For example, a high score for `NetworkClient` means many parts of the app have dependency paths through that client. This may be expected for a shared network service. An unexpected score can help identify code that's doing too much coordination. Review its responsibilities and the APIs its consumers depend on.

### Dominator Analysis

A binding `D` dominates a binding `N` if every path from the graph root to `N` passes through `D`. A high dominator count means many bindings can only be reached through that binding.

For example, if `AuthManager` dominates `UserProfile`, every path from the graph root to `UserProfile` passes through `AuthManager`. Check whether that relationship is intentional. Unexpected dominance can help identify dependencies that are coupled too closely.

### Longest Path Analysis

This measures the deepest path through the eager binding graph. Graph accessor relationships and deferred dependencies are excluded. Aliases and supplied graph inputs count as bindings.

For example, `A → B → C → D` contains four bindings and three dependency edges. The analysis reports a length of four. Several paths can have the same length. The viewer highlights the first reported path.

Long paths give you more dependencies to inspect when debugging initialization. Look for intermediate classes that only delegate to another class. Consider whether those layers are useful. `Provider` and `Lazy` can defer initialization when a dependency isn't needed immediately.

### Shortest Paths to Root

The JSON report's `pathsToRoot` field uses eager dependencies from the recorded graph instance binding. Accessor connections and deferred dependencies are excluded. The result is empty when no graph instance binding is recorded. Bindings unreachable from that instance have empty path lists.

The viewer's **Route from root** action starts from recorded accessors and injectors. It includes deferred dependencies. Use this action to trace how a root reaches a binding.

### Root and Leaf Analysis

The `rootBindings` statistic counts nodes with no dependents in the analysis graph. The viewer's roots are recorded accessors and injectors. These counts describe different things.

Leaves have no dependencies. Configuration values, constants, and external dependencies are common examples.

A binding that's both a root and a leaf is isolated. Nothing depends on it and it has no dependencies. Check whether it's still needed.

## Programmatic Access

You can read the JSON reports in your own analysis tools.

The serializable report models are published in `dev.zacsweers.metro:graphs`. The Metro Gradle plugin exposes these models as an API dependency. Their existing `dev.zacsweers.metro.gradle.analysis` package names are unchanged.

Model APIs require opting into `dev.zacsweers.metro.graph.ExperimentalMetroGraphApi`. The Gradle plugin's `BindingGraph` and `GraphAnalyzer` helpers also require `ExperimentalMetroGradleApi`.

### Raw Metadata

The raw graph metadata from `generateInternalDebugMetroGraphMetadata`:

```kotlin
@file:OptIn(dev.zacsweers.metro.graph.ExperimentalMetroGraphApi::class)

// Parse raw graph metadata
val metadata = Json.decodeFromString<AggregatedGraphMetadata>(
    file("build/reports/metro/internalDebug/graphMetadata.json").readText()
)

// Analyze bindings
val scopedCount = metadata.graphs.sumOf { graph ->
    graph.bindings.count { it.isScoped }
}
println("Total scoped bindings: $scopedCount")

// Check roots
for (graph in metadata.graphs) {
    println("Graph: ${graph.graph}")
    graph.roots?.let { roots ->
        println("  Accessors: ${roots.accessors.size}")
        println("  Injectors: ${roots.injectors.size}")
    }
    graph.extensions?.let { ext ->
        println("  Extension factories: ${ext.factoriesImplemented.size}")
    }
}
```

The raw metadata includes:

- **roots** - Accessor and injector roots
    - `accessors` - Property and function roots, including inherited multibinding requests
    - `injectors` - Functions that inject dependencies into targets
- **extensions** - Graph extension information
    - `accessors` - Non-factory extension accessors
    - `factoryAccessors` - Factory accessors (with `isSAM` flag)
    - `factoriesImplemented` - Factory interfaces this graph implements
- **bindings** - All bindings with their kinds, scopes, and dependencies
- Multibinding information (sources, collection type)
- Origin locations (file and line numbers)
- Synthetic binding flags

### Analysis Report

The `analyzeInternalDebugMetroGraph` report groups its results by graph:

```kotlin
@file:OptIn(dev.zacsweers.metro.graph.ExperimentalMetroGraphApi::class)

// Parse analysis report
val report = Json.decodeFromString<FullAnalysisReport>(
    file("build/reports/metro/internalDebug/analysis.json").readText()
)

// Each graph has all its analysis co-located
for (graph in report.graphs) {
    println("Graph: ${graph.graphName}")
    println("  Bindings: ${graph.statistics.totalBindings}")
    println("  Scoped: ${graph.statistics.scopedBindings}")
    println("  Longest path: ${graph.longestPath.longestPathLength}")

    // High fan-in bindings
    graph.fanAnalysis.highFanIn.take(3).forEach { binding ->
        println("  High fan-in: ${binding.key} (${binding.fanIn} dependents)")
    }
}
```

The analysis report structure:

```kotlin
data class FullAnalysisReport(
    val projectPath: String,
    val graphs: List<GraphAnalysis>  // All analysis grouped by graph
)

data class GraphAnalysis(
    val graphName: String,
    val statistics: GraphStatistics,    // Binding counts, averages
    val longestPath: LongestPathResult, // Deepest dependency chains
    val dominator: DominatorResult,     // Dominator tree analysis
    val centrality: CentralityResult,   // Betweenness centrality scores
    val fanAnalysis: FanAnalysisResult, // Fan-in/fan-out metrics
    val pathsToRoot: PathsToRootResult  // Shortest paths from each node to graph root
)
```
