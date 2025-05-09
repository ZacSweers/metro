// ENABLE_DAGGER_INTEROP

// FILE: ExampleClass.java
import javax.inject.Inject;

public class ExampleClass {
  @Inject public ExampleClass() {

  }
}

// FILE: ExampleGraph.kt
import kotlin.test.*

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.exampleClass)
  return "OK"
}
