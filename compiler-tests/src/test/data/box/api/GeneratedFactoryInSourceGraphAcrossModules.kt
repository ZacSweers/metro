// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// GENERATE_CLASSES_IN_IR: false

// MODULE: lib
package test

@Target(AnnotationTarget.CLASS)
annotation class GenerateGraphExtensionFactory

// The extension generates a contributed Factory without returning an explicit hint for it.
@GenerateGraphExtensionFactory
@GraphExtension(Unit::class)
interface LoginGraph {
  val text: String
}

// MODULE: main(lib)
package test

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val loginGraph = createGraph<AppGraph>().create("hello")
  assertEquals("hello", loginGraph.text)
  return "OK"
}
