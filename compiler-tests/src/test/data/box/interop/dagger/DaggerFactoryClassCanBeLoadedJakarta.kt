// ENABLE_DAGGER_KSP

// FILE: ExampleClass.java
import jakarta.inject.Inject;

public class ExampleClass {
  @Inject public ExampleClass() {

  }
}

// FILE: ExampleGraph.kt
@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.exampleClass)
  return "OK"
}
