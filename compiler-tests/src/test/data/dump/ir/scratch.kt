// ENABLE_DAGGER_INTEROP

// MODULE: main
// FILE: ExampleClass.java

package test;

import javax.inject.Inject;

public class ExampleClass {
  @Inject public ExampleClass() {

  }
}

// FILE: ExampleGraph.kt

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass
}
