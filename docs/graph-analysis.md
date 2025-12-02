# Graph Analysis & Visualization

Metro provides Gradle tasks for analyzing and visualizing dependency graphs. These tools help you understand your dependency structure, identify potential issues, and debug complex graphs.

## Setup

Graph analysis requires setting the `reportsDestination` property in your Metro configuration:

!!! warning
    You should _not_ leave this enabled by default as it can be quite verbose and potentially expensive. This property also does not participate in task inputs, so you may need to recompile with `--rerun` to force recompilation after adding this flag.

```kotlin
metro {
  reportsDestination.set(layout.buildDirectory.dir("reports/metro"))
}
```

This enables the compiler to export graph metadata during compilation.

## Available Tasks

!!! warning
    These tasks are purely for analysis and visualization. They are not intended for continuous validation at the moment due to the caveats with `reportsDestination` mentioned above.

### `generateMetroGraphMetadata`

Generates raw JSON metadata files for each dependency graph in your project. This task runs automatically during compilation when `reportsDestination` is set.

**Output:** `{reportsDestination}/{sourceSet}/graph-metadata/graph-{fully.qualified.GraphName}.json`

You typically don't need to run this task directly, it's a dependency of the other analysis tasks.

### `analyzeMetroGraph`

Aggregates all graph metadata and produces a comprehensive analysis report.

```bash
./gradlew :app:analyzeMetroGraph
```

**Output:** `build/reports/metro/analysis.json`

This task combines all individual graph JSON files into a single aggregated file and runs various graph analysis algorithms. The output can be used for further analysis or consumed by other tools.

### `generateMetroGraphHtml`

Generates interactive HTML visualizations of your dependency graphs using [Apache ECharts](https://echarts.apache.org/).

```bash
./gradlew :app:generateMetroGraphHtml
```

**Output:** `{reportsDestination}/html/` containing:

- `index.html` - Landing page listing all graphs
- `{graph-name}.html` - Interactive visualization for each graph

Open the HTML files directly in a browser, they're fully self-contained with no external dependencies.

## Interactive Visualization Features

The generated HTML visualizations provide powerful tools for exploring your dependency graphs:

### Navigation

- **Drag** nodes to rearrange the layout
- **Scroll** to zoom in/out
- **Click** a node to view its details and highlight the path back to the graph root
- **Double-click** or press **ESC** to clear path highlighting
- **Hover** over nodes and edges for tooltips

### Layout Modes

- **Force** - Physics-based layout that automatically positions nodes
- **Circular** - Arranges nodes in a circle

### Filtering

Multiple filters can be combined to focus on specific parts of your graph:

| Filter                          | Description                                                               |
|---------------------------------|---------------------------------------------------------------------------|
| **Search**                      | Filter nodes by name or full type key                                     |
| **Show synthetic bindings**     | Toggle visibility of generated/internal bindings (aliases, contributions) |
| **Show only scoped bindings**   | Hide non-scoped bindings to focus on singletons                           |
| **Show default value bindings** | Toggle visibility of synthetic nodes for default parameter values         |
| **Show metrics glow**           | Toggle the glow effects highlighting nodes with notable metrics           |
| **Package filter**              | Toggle visibility by package (collapsed by default)                       |

### Analysis Tools

- **Show Longest Path** - Highlights the longest dependency chain in your graph, useful for identifying deep dependency trees

## Understanding the Visualization

### Node Shapes

| Shape                          | Meaning                      |
|--------------------------------|------------------------------|
| **Diamond**                    | Main `@DependencyGraph`      |
| **Rounded Rectangle**          | `@GraphExtension`            |
| **Circle with magenta border** | Scoped binding (`@SingleIn`) |
| **Circle**                     | Regular binding              |

Larger nodes indicate more significant bindings (graphs, extensions, scoped).

!!! tip "Full Legend"
    The HTML visualization includes a complete interactive legend at the bottom of the chart showing all binding kinds and their colors. Expand the "Edge Types" section in the sidebar for edge styling details.

### Node Colors

Nodes are colored by binding kind:

| Color      | Binding Kind                                            |
|------------|---------------------------------------------------------|
| Blue       | Constructor-injected (`@Inject`)                        |
| Green      | Provided (`@Provides`)                                  |
| Gray       | Alias (`@Binds`)                                        |
| Light Blue | Bound instance (graph itself or `@Provides` parameters) |
| Pink       | Multibinding (`Set<T>` or `Map<K,V>`)                   |
| Purple     | Graph extension                                         |
| Peach      | Assisted injection                                      |

Synthetic (generated) bindings appear gray and with reduced opacity. They do give you a good sense of the glue that Metro generates behind the scenes.

### Metrics Glow Effects

Nodes with notable analysis metrics are highlighted with glow effects to draw attention to potential architectural concerns:

| Glow Color | Trigger                          | Meaning                                              |
|------------|----------------------------------|------------------------------------------------------|
| **Red**    | Centrality in top 10%            | Critical connector - many paths flow through it      |
| **Yellow** | Centrality in top 25%            | Moderate connector - notable traffic hub             |
| **Red**    | Dominator count > 10% of graph   | Dominates many bindings - initialization bottleneck  |
| **Blue**   | Fan-in in top 10%                | Highly depended-upon - changes affect many consumers |

!!! note "Dynamic Thresholds"
    Glow thresholds are computed dynamically based on graph size and metrics distribution. This ensures meaningful highlighting for both small (10 nodes) and large (500+ nodes) graphs.

Use the "Show metrics glow" filter to toggle these effects on/off.

### Metrics Heatmap Colors

In tooltips and the details panel, analysis metrics are color-coded by severity:

| Metric              | Blue (Good) | Yellow (Moderate) | Red (High)     |
|---------------------|-------------|-------------------|----------------|
| **Fan-in**          | ≤ 5         | 6-10              | > 10           |
| **Fan-out**         | ≤ 4         | 5-8               | > 8            |
| **Centrality**      | ≤ 10%       | 10-30%            | > 30%          |
| **Dominator count** | ≤ 5         | 6-10              | > 10           |

These thresholds help identify bindings that may warrant architectural review.

### Edge Types

Edges are styled to indicate the relationship type:

| Style                    | Meaning                        | What to Look For                                                      |
|--------------------------|--------------------------------|-----------------------------------------------------------------------|
| **Gray, solid**          | Normal dependency              | Standard injection                                                    |
| **Light blue, thick**    | Accessor (graph entry point)   | These are your graph's public API (accessor properties and functions) |
| **Magenta, dashed**      | Inherited binding              | Extension accessing parent graph's scoped binding                     |
| **Cyan, dashed**         | Deferrable (`Provider`/`Lazy`) | Often used to defer initialization or break cycles                    |
| **Orange, thick**        | Assisted injection             | Runtime parameters passed to factories                                |
| **Purple**               | Multibinding contribution      | Source bindings feeding into a multibound `Set` or `Map`              |
| **Gray, dotted**         | Alias                          | `@Binds` type mapping                                                 |
| **Gray, dashed (faded)** | Optional                       | Has a default value                                                   |

## Reading the Analysis

### Understanding Graph Structure

**Entry points (roots):**
The graph's entry points are tracked in the `roots` metadata object, separate from binding dependencies. This includes:

- **Accessors** - Properties on the graph interface that expose bindings (e.g., `val serviceA: ServiceA`)
- **Injectors** - Functions that inject dependencies into targets (e.g., `fun inject(target: Activity)`)

Light blue edges from the main graph (diamond) show these entry points—your graph's public API. The analysis infrastructure creates edges from the graph to accessor targets when building the graph structure.

**Graph extensions:**
Rounded rectangle nodes show `@GraphExtension` types. Extension information is tracked in the `extensions` metadata object:

- **accessors** - Non-factory extension accessors
- **factoryAccessors** - Factory accessors that create extension instances
- **factoriesImplemented** - Factory interfaces this graph implements

Magenta dashed edges indicate which scoped bindings extensions inherit from the parent graph.

**Binding flow:**
Follow edges from entry points inward to understand how dependencies are resolved. The direction of arrows shows the "depends on" relationship.

## Tips

### Identifying Issues

**Deep dependency chains:**
Use "Show Longest Path" to find the deepest dependency chain. Very long paths may indicate:

- Overly coupled code
- Missing abstractions
- Opportunities to defer dependencies with `Provider`/`Lazy`

**Too many scoped bindings:**
Filter to "Show only scoped bindings". If you have many scoped bindings:

- Consider if all truly need to be singletons
- Scoped bindings add memory overhead and complexity
- Some may be candidates for unscoped bindings

**Complex multibindings:**
Look for pink nodes with many incoming purple edges. Large multibindings may indicate:

- Plugin systems that could be simplified
- Opportunities to use more targeted bindings

**Circular dependencies:**
While Metro prevents true cycles, you may see near-cycles broken by `Provider`/`Lazy` (cyan dashed edges). Many of these may indicate:

- Tightly coupled components
- Opportunities for refactoring

### Performance

For large graphs, the force layout may take a moment to stabilize. You can:

- Use the circular layout for a quicker overview
- Filter to specific packages to reduce complexity
- Hide synthetic bindings to focus on your code

### Debugging

When investigating a specific binding:

1. Use the search box to find it
2. Click the node to see its details panel
3. Review its dependencies and dependents
4. Follow edges to understand the resolution path

### Sharing

The HTML files are self-contained and can be:

- Committed to version control for historical comparison
- Shared with team members
- Attached to code reviews for dependency discussions

## Example Workflow

```bash
# Generate visualizations
./gradlew :app:generateMetroGraphHtml

# Open in browser
open app/build/reports/metro/html/index.html
```

Then in the visualization:

1. Click your main graph in the index
2. Use "Show Longest Path" to understand depth
3. Filter to "Show only scoped bindings" to review singletons
4. Search for specific types you're investigating
5. Click nodes to explore their dependencies

## Analysis Metrics

The `analyzeMetroGraph` task computes several metrics that help identify architectural issues. Here's what each metric means and how to use it.

### Fan-In and Fan-Out

**What it measures:** How many things depend on a binding (fan-in) and how many things a binding depends on (fan-out).

| Metric      | Meaning                                          |
|-------------|--------------------------------------------------|
| **Fan-In**  | Number of other bindings that depend on this one |
| **Fan-Out** | Number of dependencies this binding requires     |

**How to interpret:**

- **High fan-in** = Many things depend on this binding. It's a "popular" dependency.
    - *Good:* Core utilities, interfaces, shared services
    - *Warning sign:* If it changes frequently, many things break
    - *Action:* Ensure high fan-in bindings have stable APIs and good test coverage

- **High fan-out** = This binding depends on many things. It has lots of dependencies.
    - *Warning sign:* May be doing too much (violates Single Responsibility)
    - *Action:* Consider breaking into smaller, focused classes

- **High fan-in AND high fan-out** = A "hub" that's both heavily used and complex
    - *Warning sign:* Changes here are risky and have wide impact
    - *Action:* Prioritize for refactoring; consider splitting responsibilities

### Betweenness Centrality

**What it measures:** How often a binding lies on the shortest path between other bindings. Think of it as measuring how much of a "bottleneck" or how sticky a binding is.

**In simple terms:** If you imagine dependencies flowing through your graph like traffic, high betweenness centrality means lots of traffic flows *through* this binding to get elsewhere.

**How to interpret:**

- **High centrality** = This binding is a critical connector in your graph
    - Many dependency chains pass through it
    - It's a potential bottleneck for initialization
    - Changes here can have ripple effects

- **What to do with high-centrality bindings:**
    - Review for stability, these should rarely change
    - Consider if they're doing too much coordination
    - May indicate a missing abstraction layer
    - Good candidates for careful interface design

**Example:** If `NetworkClient` has high betweenness centrality, it means many different parts of your app depend on things that go through `NetworkClient`. This is expected for infrastructure, but surprising for business logic.

### Dominator Analysis

**What it measures:** A binding D "dominates" binding N if *every* path from the graph root to N must go through D. In other words, you can't reach N without first going through D.

**In simple terms:** Dominators are mandatory waypoints. If `AuthManager` dominates `UserProfile`, then there's no way to create a `UserProfile` without first having an `AuthManager`.

**How to interpret:**

- **High dominator count** = Many bindings can only be reached through this one
    - It's a gatekeeper in your dependency structure
    - If it fails to initialize, everything it dominates also fails

- **What to do with high-dominator bindings:**
    - Ensure they initialize quickly and reliably
    - Consider if the dominance is intentional (auth gates make sense) or accidental
    - May indicate overly tight coupling if unexpected
    - Good candidates for early initialization and error handling

**Example:** If `DatabaseConnection` dominates 50 bindings, all 50 of those bindings require the database. Ask: do they all *really* need the database, or could some work offline?

### Longest Path Analysis

**What it measures:** The deepest chain of dependencies from any entry point to a leaf binding.

**In simple terms:** How many "hops" does the deepest dependency chain take? If creating `A` requires `B` which requires `C` which requires `D`, that's a path of length 4.

**How to interpret:**

- **Long paths** (10+ nodes) may indicate:
    - Deeply nested architecture
    - Potential for slow initialization (each hop adds time)
    - Complex debugging when something fails deep in the chain

- **What to do about long paths:**
    - Look for opportunities to flatten the hierarchy
    - Consider if intermediate layers add value
    - Use `Provider`/`Lazy` to defer initialization of deep branches
    - May indicate "wrapper" classes that just delegate

### Shortest Paths to Root

**What it measures:** The shortest path from each binding back to the graph root, computed using Dijkstra's algorithm.

**In simple terms:** For any binding, what's the most direct route back to where it's consumed by the graph? This is precomputed during analysis and used to power the path highlighting feature in the visualization.

**How to use it:**

- **Click any node** in the visualization to highlight its path back to the graph root
- The path shows the minimum number of hops to reach that binding from the graph's entry points
- Useful for understanding how deeply nested a binding is in the dependency structure
- Helps trace the resolution path when debugging injection issues

**In the analysis JSON:**
```kotlin
val pathsToRoot = graph.pathsToRoot
val path = pathsToRoot.paths["com.example.MyService"]
// Returns: ["MyService", "MyRepository", "AppGraph"] (from binding to root)
```

### Root and Leaf Analysis

**What it measures:**

- **Roots:** Bindings with no dependents (nothing depends on them). These are typically your entry points — accessors on the graph.
- **Leaves:** Bindings with no dependencies. These are the "bottom" of your graph — things that don't need anything else.

**How to interpret:**

- **Many roots** = Your graph exposes many entry points
    - Expected for large graphs with rich APIs
    - Consider if all roots are necessary

- **Many leaves** = Lots of "primitive" bindings at the bottom
    - Often configuration values, constants, or external dependencies
    - Expected for well-factored code

- **Binding that's both root AND leaf** = Isolated binding
    - Nothing depends on it and it depends on nothing
    - Worth investigating, this might be dead code

### Putting It All Together

When analyzing a graph, look for patterns:

| Pattern                       | What It Suggests                | Action                               |
|-------------------------------|---------------------------------|--------------------------------------|
| High fan-in + high centrality | Critical infrastructure binding | Stabilize API, add tests             |
| High fan-out + low fan-in     | Complex internal implementation | Consider splitting                   |
| High dominator count          | Initialization bottleneck       | Ensure fast, reliable init           |
| Very long paths               | Deep coupling                   | Look for flattening opportunities    |
| High fan-in + high dominator  | True architectural cornerstone  | Document, protect, version carefully |

## Programmatic Access

The JSON outputs can be consumed programmatically for custom analysis.

### Raw Metadata

The raw graph metadata from `generateMetroGraphMetadata`:

```kotlin
// Parse raw graph metadata
val metadata = Json.decodeFromString<AggregatedGraphMetadata>(
    file("build/reports/metro/graphMetadata.json").readText()
)

// Analyze bindings
val scopedCount = metadata.graphs.sumOf { graph ->
    graph.bindings.count { it.isScoped }
}
println("Total scoped bindings: $scopedCount")

// Check entry points
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

- **roots** - Entry points into the graph
    - `accessors` - Properties exposing bindings from the graph
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

The analysis report from `analyzeMetroGraph` is organized by graph, with all analysis data grouped together:

```kotlin
// Parse analysis report
val report = Json.decodeFromString<FullAnalysisReport>(
    file("build/reports/metro/analysis.json").readText()
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
