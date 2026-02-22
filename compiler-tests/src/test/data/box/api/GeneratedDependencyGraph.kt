package test

import kotlin.reflect.KClass

// Custom annotation that triggers the test extension to generate a @DependencyGraph
// with a @DependencyGraph.Factory inside the annotated class
@Target(AnnotationTarget.CLASS)
annotation class GenerateGraphFactory

@ContributesTo(AppScope::class)
interface StringComponent {
  val text: String
}

@GenerateGraphFactory
class Application

// The extension generates a nested interface inside Application:
//   @DependencyGraph(AppScope::class)
//   interface AppGraph {
//     @DependencyGraph.Factory
//     fun interface Factory {
//       fun create(@Provides text: String): AppGraph
//     }
//   }

fun box(): String {
  val graph = createGraphFactory<Application.AppGraph.Factory>().create("hello")
  assertEquals("hello", graph.text)
  return "OK"
}
