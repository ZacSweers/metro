// MODULE: lib
@GraphExtension(String::class)
interface ChildGraph {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  fun interface Factory {
    fun create(): ChildGraph
  }
}

// MODULE: main(lib)
// WITH_REFLECT
// ENABLE_GRAPH_IMPL_CLASS_AS_RETURN_TYPE

import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaType

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val parentGraph = createGraph<AppGraph>()
  val generatedMetroGraphClass = parentGraph.javaClass.classes.single { it.simpleName == "ChildGraphImpl" }

  // In IR we change the return type of the implemented create() function from ChildGraph to
  // ParentGraph.Impl.ChildGraphImpl. The Kotlin compiler creates two functions in
  // the generated class file, but in IR only one is visible:
  //
  // public final fun create(..): ParentGraph.Impl.ChildGraphImpl
  // public fun create(..): ChildGraph
  //
  // Because one of the two functions only exist in Java bytecode, we can only see it through
  // Java reflection and not Kotlin reflection.
  val javaFunctions = parentGraph.javaClass.methods.filter { it.name == "create" }
  assertEquals(2, javaFunctions.size)

  assertTrue(generatedMetroGraphClass.isInstance(javaFunctions.single { it.returnType == Class.forName("ChildGraph") }.invoke(parentGraph)))
  assertTrue(generatedMetroGraphClass.isInstance(javaFunctions.single { it.returnType == generatedMetroGraphClass }.invoke(parentGraph)))

  val kotlinFunction = parentGraph::class.functions.single { it.name == "create" }
  assertTrue(generatedMetroGraphClass.isInstance(kotlinFunction.call(parentGraph)))
  assertEquals(Class.forName("ChildGraph"), kotlinFunction.returnType.javaType)

  return "OK"
}
