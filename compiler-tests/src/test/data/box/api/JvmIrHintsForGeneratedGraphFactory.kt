// METRO_JVM_ONLY
// MIN_COMPILER_VERSION: 2.4.20
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// GENERATE_JVM_CONTRIBUTION_HINTS_IN_IR
// GENERATE_CLASSES_IN_IR: true
// MODULE: lib
package test

@Target(AnnotationTarget.CLASS)
annotation class GenerateGraphExtensionFactory

// No explicit external hint is returned for this nested generated factory.
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
