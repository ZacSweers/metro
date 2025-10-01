// ENABLE_DAGGER_KSP

// FILE: ExampleClass.java
import javax.inject.Inject;
import javax.inject.Named;

@Named("qualifier")
public class ExampleClass {
  @Inject public ExampleClass() {

  }
}

// FILE: ExampleGraph.kt
import javax.inject.Named

@DependencyGraph
interface ExampleGraph {
  @Named("qualifier") val exampleClass: ExampleClass
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.exampleClass)
  return "OK"
}
