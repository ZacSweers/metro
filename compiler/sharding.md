# Graph Sharding

This document describes the implementation of graph sharding in Metro's compiler plugin.

## Problem

When a dependency graph has many bindings, the generated `Impl` graph implementation class can exceed JVM class file size limits.

## Solution Overview

Sharding distributes initialization logic across multiple inner classes ("shards") while keeping provider fields on the main graph class. Each shard is responsible for initializing a subset of provider fields.

### Key Design Decisions

1. **Provider fields stay on the main class** — Bindings are accessed via fields on the graph class itself, ensuring consistent access patterns regardless of sharding.

2. **Shards are inner classes** — As inner classes, shards have implicit access to the outer graph's `this` receiver, allowing direct field assignment without explicit parameters.

3. **Shards are instantiated inline** — Shard instances are created and used immediately (`Shard1().initialize()`), not stored in fields. This reduces the main class size further.

4. **SCC-aware partitioning** — Bindings that form dependency cycles (broken by `Provider`/`Lazy`) are kept together in the same shard to maintain correct initialization order.

## Architecture

### Files

```
compiler/src/main/kotlin/dev/zacsweers/metro/compiler/
├── graph/
│   ├── GraphPartitioner.kt      # SCC-aware partitioning algorithm
│   └── sharding/
│       ├── IrGraphShardGenerator.kt  # IR code generation for shards
│       └── ShardingDiagnostics.kt    # Diagnostic report generation
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
    // Provider fields (visibility relaxed to protected on JVM)
    protected val service1Provider: Provider<Service1>
    protected val service2Provider: Provider<Service2>
    // ...

    private inner class Shard1 {
        fun initialize() {
            service1Provider = provider { Service1.MetroFactory.create() }
            service2Provider = provider { Service2.MetroFactory.create(service1Provider) }
        }
    }

    private inner class Shard2 {
        fun initialize() {
            // ... more initializations
        }
    }

    init {
        Shard1().initialize()
        Shard2().initialize()
    }
}
```

### JVM Visibility Considerations

On JVM, inner classes are compiled as separate class files. Even though shards use the outer class's `this` receiver (via inner class implicit access), `irSetField()` generates direct field access bytecode. The Kotlin compiler only generates synthetic accessors for **source code** that the frontend analyzes — our IR is generated after that phase, so no accessors are created.

We must relax backing field visibility from private to protected:

```kotlin
// In IrGraphShardGenerator.generateShards()
if (pluginContext.platform.isJvm()) {
    for (binding in propertyBindings) {
        binding.property.backingField?.visibility = DescriptorVisibilities.PROTECTED
    }
}
```

`protected` maps to package-private + subclass access in JVM bytecode, which is more restrictive than `internal` (which becomes public). This is the minimum visibility that allows inner class access while avoiding full exposure.

Non-JVM platforms (Native, JS, Wasm) don't have this restriction since they handle inner class access differently.

### Chunking Within Shards

If a shard has many statements, they're further chunked into private `init1()`, `init2()`, etc. functions to avoid method size limits:

```kotlin
private inner class Shard1 {
    private fun init1() { /* first batch */ }
    private fun init2() { /* second batch */ }

    fun initialize() {
        init1()
        init2()
    }
}
```

This is controlled by `statementsPerInitFun` (default: 25).

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
