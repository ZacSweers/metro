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
// SUPPRESS_WARNINGS: METRO_WARNING
// CHECK_COMPILER_OUTPUT

// FILE: ParentGraph.kt
@DependencyGraph(bindingContainers = [ExternalModule::class])
interface ParentGraph
