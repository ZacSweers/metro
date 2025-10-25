// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: ExampleClass.java
package test;

import javax.inject.Inject;
import javax.inject.Provider;
import dagger.Lazy;

public class ExampleClass {
  @Inject public ExampleClass(String value, Provider<String> provider, Lazy<String> lazy) {

  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface ExampleGraph {
  val exampleClass: ExampleClass

  @Provides fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.exampleClass)
  return "OK"
}
