// RUN_PIPELINE_TILL: BACKEND
// METRO_JVM_ONLY

// The binary Dagger module has no source location.
// Its warning uses Metro's collector fallback.

// MODULE: lib
// WITH_DAGGER
// DISABLE_METRO

// FILE: Child.java
@dagger.Subcomponent
public interface Child {}

// FILE: ExternalModule.java
@dagger.Module(subcomponents = Child.class)
public class ExternalModule {}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
// UNUSED_GRAPH_INPUTS_SEVERITY: NONE
// EXPECT_METRO_COLLECTOR_WARNING: Included Dagger module 'ExternalModule' declares a `subcomponents` parameter but this will be ignored by Metro in interop.

// FILE: ParentGraph.kt
@DependencyGraph(bindingContainers = [ExternalModule::class])
interface ParentGraph
