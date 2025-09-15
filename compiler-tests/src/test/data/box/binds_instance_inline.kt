// TEST_TARGET: jvm
// Test that @BindsInstance parameters work correctly with inline generation

import dev.zacsweers.metro.BindsInstance

@Inject class NeedsString(val str: String)

@DependencyGraph
interface StringGraph {
  val needsString: NeedsString

  interface Creator {
    fun create(@BindsInstance str: String): StringGraph
  }
}

fun box(): String {
  val testString = "Hello Metro!"
  val graph = createGraph<StringGraph>(testString)

  // Verify the bound instance is properly passed through
  if (graph.needsString.str != testString) {
    return "FAIL: Expected '$testString' but got '${graph.needsString.str}'"
  }

  // Verify singleton semantics
  val ns1 = graph.needsString
  val ns2 = graph.needsString
  if (ns1 !== ns2) {
    return "FAIL: needsString should return the same instance"
  }

  return "OK"
}