// ENABLE_DAGGER_KSP

// FILE: ExampleClass.java
import javax.inject.Inject;

public class ExampleClass<T> {
  public final T value;

  @Inject public ExampleClass(T value) {
    this.value = value;
  }
}

// FILE: ExampleGraph.kt
@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass<Int>

  @Provides val int: Int get() = 3
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val intClass = graph.exampleClass
  assertEquals(3, intClass.value)
  return "OK"
}
