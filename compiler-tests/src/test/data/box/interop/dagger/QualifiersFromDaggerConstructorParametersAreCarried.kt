// ENABLE_DAGGER_KSP

// FILE: ExampleClass.java
import javax.inject.Inject;
import javax.inject.Named;

public class ExampleClass {
  final String value;
  @Inject public ExampleClass(@Named("qualifier") String value) {
    this.value = value;
  }
}

// FILE: ExampleGraph.kt
import javax.inject.Named

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass
  @Named("qualifier") @Provides fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("hello", graph.exampleClass.value)
  return "OK"
}
