# Metro Graph Analysis & Visualization

This document describes the graph analysis and visualization system for Metro dependency graphs.

## Overview

The system generates interactive HTML visualizations of Metro dependency graphs using [Apache ECharts](https://echarts.apache.org/). It consists of three main phases:

1. **Metadata Generation** (Compiler) - The compiler plugin exports graph metadata as JSON during IR transformation
2. **Metadata Aggregation** (Gradle) - Gradle tasks aggregate per-graph JSON files into a single file
3. **HTML Generation** (Gradle) - Generates interactive HTML visualizations from the aggregated metadata

## Architecture

```
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│  GraphMetadata      │     │  AnalyzeGraphTask   │     │ GenerateGraphHtml   │
│  Reporter           │────▶│  (aggregation)      │────▶│ Task                │
│  (compiler/ir)      │     │                     │     │                     │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
        │                           │                           │
        ▼                           ▼                           ▼
   graph-*.json              aggregated.json              *.html files
   (per graph)               (all graphs)                 (interactive)
```

## Key Files

### Compiler Side
- `compiler/.../ir/graph/GraphMetadataReporter.kt` - Exports binding graph metadata to JSON

### Gradle Plugin Side
- `GraphMetadataModels.kt` - Kotlinx Serialization data classes for JSON parsing
- `AnalyzeGraphTask.kt` - Aggregates individual graph JSON files
- `GenerateGraphHtmlTask.kt` - Generates interactive HTML visualizations
- `GraphAnalyzer.kt` - Analysis utilities (cycle detection, metrics, etc.)

## Data Models

### GraphMetadata
Top-level metadata for a dependency graph:
```kotlin
data class GraphMetadata(
  val graph: String,                    // Fully qualified graph class name
  val scopes: List<String>,             // Scope annotations
  val aggregationScopes: List<String>,  // Aggregation scope class names
  val roots: RootsMetadata?,            // Entry points (accessors, injectors)
  val extensions: ExtensionsMetadata?,  // Graph extension information
  val bindings: List<BindingMetadata>,  // All bindings in the graph
)
```

### RootsMetadata
Entry points into the graph (separate from binding dependencies):
```kotlin
data class RootsMetadata(
  val accessors: List<AccessorMetadata>,  // val serviceA: ServiceA
  val injectors: List<InjectorMetadata>,  // fun inject(target: Foo)
)
```

### ExtensionsMetadata
Graph extension information:
```kotlin
data class ExtensionsMetadata(
  val accessors: List<ExtensionAccessorMetadata>,         // Extension accessors
  val factoryAccessors: List<ExtensionFactoryAccessorMetadata>,  // Factory accessors
  val factoriesImplemented: List<String>,                 // Factory interfaces this graph implements
)
```

### BindingMetadata
Represents a single binding in the graph:
```kotlin
data class BindingMetadata(
  val key: String,           // Full type key (may include annotations/generics)
  val bindingKind: String,   // ConstructorInjected, Provided, Alias, Multibinding, etc.
  val isScoped: Boolean,
  val dependencies: List<DependencyMetadata>,
  val isSynthetic: Boolean,  // Generated/internal bindings (aliases, contributions)
  // ... other fields
)
```

### DependencyMetadata
Represents a dependency edge:
```kotlin
data class DependencyMetadata(
  val key: String,           // Type key of the dependency
  val isDeferrable: Boolean, // Wrapped in Provider/Lazy (breaks cycles)
  val isAssisted: Boolean,   // Assisted injection parameter
)
```

Note: Accessors are tracked only in the `roots` object, not as dependencies of the graph's BoundInstance binding.
The `BindingGraph` class creates edges from the graph to accessor targets when building the graph structure,
maintaining clean semantic separation while preserving graph connectivity for analysis.

## Analysis Result Models

Analysis results are organized by graph in `AnalysisResults.kt`. Each graph's analysis is grouped together in a `GraphAnalysis` object:

```kotlin
// Top-level report containing all graphs
data class FullAnalysisReport(
  val projectPath: String,
  val graphs: List<GraphAnalysis>  // All analysis grouped per-graph
) {
  val graphCount: Int get() = graphs.size  // Computed property
}

// All analysis for a single graph, co-located
data class GraphAnalysis(
  val graphName: String,
  val statistics: GraphStatistics,
  val longestPath: LongestPathResult,
  val dominator: DominatorResult,
  val centrality: CentralityResult,
  val fanAnalysis: FanAnalysisResult,
  val pathsToRoot: PathsToRootResult,
)
```

Note: Individual result types (`GraphStatistics`, `LongestPathResult`, etc.) do NOT contain `graphName` since it's in the parent `GraphAnalysis`. This avoids redundancy and makes the structure more intuitive to navigate.

## Type Key Handling

Type keys can be complex strings with annotations, generics, or wrapper types. Three helper functions handle these:

### `unwrapTypeKey(key: String): String`
Unwraps Provider/Lazy wrappers to find the actual target node:
- `Provider<com.example.Foo>` → `com.example.Foo`
- `Lazy<com.example.Bar>` → `com.example.Bar`

### `extractDisplayName(key: String): String`
Extracts a human-readable short name:
- `kotlin.collections.Set<com.example.Plugin>` → `Set<Plugin>`
- `@annotation.Foo(...) com.example.Bar` → `Bar`
- `com.example.Companion` → `EnclosingClass.Companion`

### `extractPackage(key: String): String`
Extracts the package for filtering:
- `kotlin.collections.Set<com.example.Plugin>` → `com.example` (from type param)
- `@annotation.Foo(...) com.example.Bar` → `com.example` (from actual type)

**Important:** Keys starting with `@` are annotation-qualified types. The format is:
```
@fully.qualified.Annotation("args") actual.type.Name
```
Always strip the annotation prefix before extracting package/display name.

## Edge Types

Links between nodes have different types for visual distinction:

| Edge Type | Color | Style | Description |
|-----------|-------|-------|-------------|
| `normal` | Gray | Solid | Regular dependency |
| `accessor` | Light Blue | Solid, thick | Graph entry point (exposed property) |
| `deferrable` | Cyan | Dashed | Provider/Lazy wrapped (breaks cycles) |
| `assisted` | Orange | Solid, thick | Assisted injection |
| `multibinding` | Purple | Solid | Multibinding source contribution |
| `alias` | Gray | Dotted | Type alias/binding |
| `optional` | Gray | Dashed, faded | Has default value |
| `inherited` | Magenta | Dashed | Inherited binding from parent graph |

## Node Categories

Nodes are colored by binding kind:
- **ConstructorInjected** - Blue
- **Provided** - Green
- **Alias** - Gray (synthetic)
- **BoundInstance** - Light Blue
- **Multibinding** - Pink
- **Assisted** - Peach
- **Scoped bindings** - Magenta border

Synthetic nodes (generated/internal) have reduced opacity (0.6).

### Special Node Shapes

The main graph and graph extensions have distinct visual styles via shape and size:

| Node Type | Shape | Size | Description |
|-----------|-------|------|-------------|
| **Main Graph** | Diamond | 28 | The root `@DependencyGraph` node |
| **Graph Extension** | RoundRect | 22 | `@GraphExtension` nodes |
| **Scoped Binding** | Circle | 20 | Any scoped binding (with magenta border) |
| **Regular Binding** | Circle | 12 | Standard bindings |

The tooltip displays special labels: `◆ GRAPH` for the main graph and `▢ EXTENSION` for extensions.

### Inherited Binding Edges

When a `@GraphExtension` accesses a scoped binding from the parent graph, the edge is styled as "inherited":
- Magenta dashed line
- Indicates the extension is reusing a binding from the parent graph

This helps visualize the relationship between graph extensions and their parent graph's bindings.

## Graph Entry Points (Accessors)

The graph's own binding (BoundInstance) includes dependencies on:
1. **Accessors** - Properties like `val serviceA: ServiceA`
2. **Injectors** - Functions like `fun inject(target: Foo)`
3. **Graph extensions** - Extension accessors and factories

This creates edges from the graph node to all exposed types.

## Shortest Paths & Path Highlighting

The analysis computes shortest paths from every node back to the graph root using Dijkstra's algorithm (via `ShortestPath` class from dependency-analysis-gradle-plugin).

### PathsToRootResult
```kotlin
data class PathsToRootResult(
  val rootKey: String,                    // The graph root node key
  val paths: Map<String, List<String>>,   // Node key -> path to root (inclusive)
)
```

Each path is a list from the node to root, e.g., `["MyService", "MyRepository", "AppGraph"]`.

### Computation
In `GraphAnalyzer.computePathsToRoot()`:
1. Create `ShortestPath` instance with graph root as source
2. For each node, get path via `shortestPath.pathTo(node)`
3. Reverse the path (Dijkstra returns root→node, we want node→root)
4. Store in map keyed by node

### Usage in Visualization
The precomputed paths are embedded in the HTML as a JavaScript object:
```javascript
const pathsToRoot = { "com.example.Foo": ["Foo", "Bar", "AppGraph"], ... };
```

When a user clicks a node:
1. Look up the precomputed path from `pathsToRoot`
2. Highlight all nodes and edges along the path
3. Fade non-path elements to 15% opacity

Clear highlighting via:
- Double-click anywhere
- Press ESC key
- Click on empty chart background

### Dynamic Glow Thresholds
Glow effect thresholds are computed dynamically based on graph size and metrics distribution:
- **High centrality**: 90th percentile of centrality values
- **Medium centrality**: 75th percentile of centrality values
- **Dominator count**: 10% of graph size (minimum 3)
- **Fan-in**: 90th percentile of fan-in values

This ensures glow effects work well for both small and large graphs.

## Filtering System

The HTML visualization supports combined filtering:

1. **Package filter** - Toggle visibility by package
2. **Synthetic filter** - Hide/show generated bindings
3. **Scoped filter** - Show only scoped bindings
4. **Search** - Filter by name/key

All filters work together - nodes must pass ALL conditions to be visible.

### Filter Implementation Notes

- Original node/link styles are deep-copied at initialization to prevent mutation
- When filtering, always explicitly set opacity (ECharts may not remove it otherwise)
- Track visible nodes in a Set during iteration, don't infer from opacity values
- Links are hidden when either source or target is hidden

```javascript
// Store originals (deep copy)
const originalNodeStyles = graphData.nodes.map(n =>
  n.itemStyle ? JSON.parse(JSON.stringify(n.itemStyle)) : {});
const originalLinkStyles = graphData.links.map(l =>
  l.lineStyle ? JSON.parse(JSON.stringify(l.lineStyle)) : {});

// Always explicitly set opacity when restoring
const style = {...originalLinkStyles[i], opacity: isVisible ? 0.7 : 0.05};
```

## Common Issues & Solutions

### Links not re-enabling after filter toggle
**Cause:** Original styles were being mutated or visibility was inferred incorrectly.
**Solution:** Deep copy original styles and track visibility explicitly in a Set.

### Wrong package for annotated types
**Cause:** `@Annotation(...) actual.Type` was parsed incorrectly.
**Solution:** Strip annotation prefix before extracting package/display name.

### Missing edges for Provider/Lazy dependencies
**Cause:** Dependency key `Lazy<X>` doesn't match node key `X`.
**Solution:** Use `unwrapTypeKey()` on dependency keys when creating links.

### Generic display names broken (e.g., "Plugin<" instead of "Set<Plugin>")
**Cause:** `substringAfterLast('.')` breaks on generic types.
**Solution:** Use `extractDisplayName()` which handles generics properly.

## Adding New Features

### New Edge Type
1. Add color constant to `Colors` object
2. Add edge type detection in `buildEChartsData()` links section
3. Add CSS class in `.edge-legend-item .edge-line.{type}`
4. Add to edge legend HTML
5. Add to tooltip `edgeLabels` map

### New Node Category
1. Add to `categoryMap` in `buildEChartsData()`
2. Add color constant to `Colors` object
3. Add to `getBindingCategories()` list

### New Special Node Type (like Graph/Extension)
1. Add detection logic in `buildEChartsData()` nodes section (check `binding.key`, `bindingKind`, etc.)
2. Add shape in the `symbol` assignment (e.g., `"diamond"`, `"roundRect"`, `"triangle"`)
3. Add border color constant to `Colors` object
4. Add size in `baseSize` assignment
5. Update tooltip to show special label
6. Add to legend if needed

### New Filter
1. Add UI element in sidebar HTML
2. Store original state if needed
3. Add filter condition in `applyFilters()` function
4. Update reset button handler
