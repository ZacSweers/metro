# Graph Sharding

This document describes the implementation of graph sharding in Metro's compiler plugin.

## Problem

When a dependency graph has many bindings, the generated `Impl` graph implementation class can exceed JVM class file size limits.

## Solution Overview

Sharding distributes provider fields across multiple **static nested classes** ("shards"). Each shard owns its subset of provider fields and initializes them in its constructor.

### Key Design Decisions

1. **Provider fields live in shards** — Each shard class owns and initializes its own provider fields. The graph class holds shard instance fields and delegates to them.

2. **Shards are static nested classes** — Shards receive an explicit `graph` parameter in their constructor rather than using implicit `this` access. This allows cleaner field ownership.

3. **Shard instances are stored as fields** — The graph class has `private val shard1: Shard1` fields. Accessors navigate through these fields to reach providers.

4. **SCC-aware partitioning** — Bindings that form dependency cycles (broken by `Provider`/`Lazy`) are kept together in the same shard to maintain correct initialization order.

5. **Reserved properties stay on graph class** — Properties exposed to child graphs (via `ParentContext`) remain on the graph class with inline getters that delegate to sharded providers.

## Architecture

### Files

```
compiler/src/main/kotlin/dev/zacsweers/metro/compiler/
├── ir/
│   ├── ParentContext.kt             # Tracks parent graph properties for child access
│   └── graph/
│       ├── BindingPropertyContext.kt    # Maps type keys to property locations
│       ├── BindingPropertyCollector.kt  # Determines which bindings need properties
│       ├── GraphPartitioner.kt          # SCC-aware partitioning algorithm
│       ├── GraphPropertyData.kt         # Property type enum and IR attributes
│       └── sharding/
│           ├── IrGraphShardGenerator.kt # IR code generation for shards
│           └── ShardingDiagnostics.kt   # Diagnostic report generation
```

### Data Flow

```
IrBindingGraph.seal()
    │
    ▼
GraphTopology (from topological sort)
    │
    ▼
GraphPartitioner.partitionBySCCs()
    │
    ▼
List<List<IrTypeKey>>  (planned shard groups)
    │
    ▼
IrGraphShardGenerator.generateShards()
    │
    ▼
List<InitStatement>  (constructor initialization statements)
```

## Partitioning Algorithm

`GraphPartitioner.kt` implements SCC-aware partitioning:

1. **Input**: `GraphTopology` containing topologically sorted keys and SCC information
2. **Process**:
   - Iterate through sorted keys in order
   - Group consecutive keys into partitions up to `keysPerGraphShard` limit
   - Never split an SCC across partitions (cycles must stay together)
3. **Output**: `List<List<T>>` where each inner list is a shard's bindings

```kotlin
// Pseudocode
fun partitionBySCCs(keysPerGraphShard: Int): List<List<T>> {
    val partitions = mutableListOf<List<T>>()
    var currentBatch = mutableListOf<T>()

    for (key in sortedKeys) {
        val sccSize = components[componentOf[key]].size

        if (sccSize > 1) {
            // Multi-node SCC - add entire cycle as a group
            if (currentBatch.size + sccSize > keysPerGraphShard) {
                flushBatch()
            }
            currentBatch.addAll(sccVertices)
        } else {
            // Single node - add individually
            if (currentBatch.size + 1 > keysPerGraphShard) {
                flushBatch()
            }
            currentBatch.add(key)
        }
    }
    return partitions
}
```

### Handling Oversized SCCs

If a single SCC exceeds `keysPerGraphShard`, it's kept together anyway. This is correct because:
- Breaking the cycle would cause incorrect initialization order
- Large SCCs are rare in practice
- A warning is emitted in diagnostics

## IR Code Generation

`IrGraphShardGenerator.kt` generates the actual IR:

### Generated Structure

```kotlin
class AppGraph$Impl : AppGraph {
    // Shard instance fields
    private val shard1: Shard1
    private val shard2: Shard2

    // Reserved properties for child graph access (with inline getters)
    protected val appService1Provider: Provider<AppService1>
        inline get() = shard1.appService1Provider

    init {
        shard1 = Shard1(this)
        shard2 = Shard2(this)
    }

    override val appService1: AppService1
        get() = shard1.appService1Provider()

    // Static nested class - owns its provider fields
    protected class Shard1(private val graph: AppGraph$Impl) {
        val appService1Provider: Provider<AppService1> =
            DoubleCheck.provider(AppService1.MetroFactory.create())

        val appService2Provider: Provider<AppService2> =
            DoubleCheck.provider(AppService2.MetroFactory.create())
    }

    protected class Shard2(private val graph: AppGraph$Impl) {
        // Can reference other shards via graph.shard1.provider
        val appService3Provider: Provider<AppService3> =
            provider { AppService3(graph.shard1.appService1Provider) }
    }
}
```

### Property Location Tracking

`BindingPropertyContext` tracks where each provider property lives:

```kotlin
sealed interface PropertyLocation {
    data object InGraphImpl : PropertyLocation
    data class InShard(val shardField: IrProperty) : PropertyLocation
}
```

When generating accessor bodies or other code that needs a provider, the location determines the access path:

- `InGraphImpl`: `this.providerProperty`
- `InShard`: `this.shardField.providerProperty`

### Reserved Properties and Child Graph Access

When child graphs (graph extensions) need to access parent bindings, `ParentContext` reserves properties on the parent graph class. These reserved properties:

1. Have **inline getters** that delegate to the actual provider (which may be in a shard)
2. Are `protected` visibility so child inner classes can access them
3. Use `PropertyType.FIELD_WITH_INLINE_GETTER` to enable compiler optimization

The inline getter pattern allows the Kotlin compiler to optimize calls to direct field access during IR lowering:

```kotlin
// Reserved property on parent graph
protected val appService1Provider: Provider<AppService1>
    inline get() = shard1.appService1Provider  // Inlined to direct field access

// Child graph can access via parent's getter
inner class ChildGraphImpl {
    init {
        // Compiler optimizes this to: parentGraph.shard1.appService1Provider
        val provider = parentGraph.appService1Provider
    }
}
```

### Initialization Order

Initialization order is critical for sharded graphs with extensions:

1. **Shard instances first** — Shards are created and their constructors run, initializing all provider fields
2. **Reserved properties second** — Reserved properties (for child access) may depend on sharded providers
3. **Deferred `setDelegate` calls last** — For cycle-breaking `DelegateFactory` instances

This order ensures that when a reserved property's backing field is initialized, the sharded provider it depends on already exists.

### Cross-Shard Dependencies

When a provider in Shard2 depends on a provider in Shard1:

```kotlin
class Shard2(private val graph: AppGraph$Impl) {
    val service3Provider: Provider<Service3> =
        provider { Service3(graph.shard1.service1Provider()) }
}
```

The shard holds a `graph` reference and navigates through it to access other shards. Since bindings are processed in topological order and shards are created in that order, forward references don't occur.

### Parent Graph Access in Extensions

For graph extensions (child graphs), accessing parent/grandparent bindings requires special handling:

```kotlin
// AppGraph -> ChildGraph -> GrandchildGraph hierarchy
class GrandchildGraphImpl {
    // parentGraph points to ChildGraphImpl
    protected val parentGraph: ChildGraphImpl = this@ChildGraphImpl

    init {
        // Accessing grandparent (AppGraph) binding:
        // Navigate: this.parentGraph.parentGraph.shard1.appService1Provider
        val provider = parentGraph.parentGraph.appService1Provider
    }
}
```

Key implementation details:
- `parentGraphClass` IR attribute stores the parent graph class reference
- `parentGraphField` IR attribute stores the explicit field (for shards that can't use implicit `this`)
- `BindingLookup.createParentGraphDependency` uses `propertyAccess.parentKey` to find the actual owning graph, not just the immediate parent on the stack

### JVM Visibility Considerations

On JVM, static nested classes are compiled as separate class files. Provider fields in shards need `protected` visibility so the graph class can access them:

```kotlin
// In IrGraphShardGenerator
if (pluginContext.platform.isJvm()) {
    shardClass.visibility = DescriptorVisibilities.PROTECTED
    for (property in shardProperties) {
        property.backingField?.visibility = DescriptorVisibilities.PROTECTED
    }
}
```

`protected` maps to package-private + subclass access in JVM bytecode.

### Chunking Within Shards

If a shard has many statements, they're further chunked into private `init1()`, `init2()`, etc. functions to avoid method size limits:

```kotlin
protected class Shard1(private val graph: AppGraph$Impl) {
    val provider1: Provider<Service1>
    val provider2: Provider<Service2>
    // ...

    init {
        init1()
        init2()
    }

    private fun init1() { /* first batch */ }
    private fun init2() { /* second batch */ }
}
```

This is controlled by `statementsPerInitFun` (default: 25).

### Graph Extensions and Dynamic Graphs

Sharding works with graph extensions and dynamic graphs:

**Graph Extensions**: Graph extensions can also be sharded. Each level in the hierarchy can have its own shards:

```kotlin
class AppGraph$Impl : AppGraph {
    protected val shard1: Shard1

    // Child graph is an inner class with its own shards
    inner class ChildGraphImpl : ChildGraph {
        protected val parentGraph: AppGraph$Impl = this@Impl
        protected val shard1: Shard1  // Child's own shard

        protected class Shard1(private val graph: ChildGraphImpl) {
            val childService1Provider: Provider<ChildService1> =
                // Can access parent's sharded providers via parentGraph
                ChildService1.MetroFactory.create(graph.parentGraph.shard1.appService1Provider())
        }
    }
}
```

**Dynamic Graphs**: Graphs created at runtime with dynamic parameters also support sharding. The sharding logic is the same; dynamic bindings are just additional bound instances that shards can reference.

## Configuration

| Option                 | Default | Description                             |
|------------------------|---------|-----------------------------------------|
| `enableGraphSharding`  | `false` | Enable/disable sharding                 |
| `keysPerGraphShard`    | `2000`  | Max bindings per shard                  |
| `chunkFieldInits`      | `true`  | Enable statement chunking within shards |
| `statementsPerInitFun` | `25`    | Max statements per init function        |

## Diagnostics

When `reportsDestination` is set, `ShardingDiagnostics.kt` generates reports:

```
=== Metro Graph Sharding Plan ===

Graph: com.example.AppGraph
Total bindings: 5000
Keys per shard limit: 2000
Shard count: 3

Initialization order: Shard1 → Shard2 → Shard3

Shard 1:
  Class: Shard1
  Bindings: 2000
  Outgoing cross-shard edges: 45
  Binding keys (first 5):
    - Service1
    - Service2
    ...
```

## Testing

- **Box tests**: `compiler-tests/src/test/data/box/dependencygraph/sharding/` - Runtime verification
- **IR dump tests**: `compiler-tests/src/test/data/dump/ir/dependencygraph/sharding/` - Generated IR verification
- **Unit tests**: `compiler/src/test/kotlin/.../GraphPartitionerTest.kt` - Partitioning algorithm

Test directives:
```kotlin
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 2
```
