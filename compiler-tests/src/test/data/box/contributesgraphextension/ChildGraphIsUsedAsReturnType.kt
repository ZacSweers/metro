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
import java.lang.reflect.Method

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val parentGraph = createGraph<AppGraph>()
  val nestedClasses = parentGraph.javaClass.declaredClasses
  val generatedMetroGraphClass = nestedClasses.singleOrNull { it.simpleName == "ChildGraphImpl" }
    ?: error(
      "No nested class found: ${nestedClasses.joinToString { it.name }}"
    )

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

  fun requireMethod(predicate: (Method) -> Boolean): Method {
    return javaFunctions.singleOrNull(predicate) ?: error(
      "No matching functions found in $javaFunctions"
    )
  }

  assertTrue(generatedMetroGraphClass.isInstance(requireMethod { it.returnType == Class.forName("ChildGraph") }.invoke(parentGraph)))
  assertTrue(generatedMetroGraphClass.isInstance(requireMethod { it.returnType == generatedMetroGraphClass }.invoke(parentGraph)))

  val parentFunctions = parentGraph::class.functions
  val kotlinFunction = parentFunctions.singleOrNull { it.name == "create" } ?: error(
    "No create function found in parent: ${parentFunctions}"
  )
  assertTrue(generatedMetroGraphClass.isInstance(kotlinFunction.call(parentGraph)))
  assertEquals(Class.forName("ChildGraph"), kotlinFunction.returnType.javaType)

  return "OK"
}
