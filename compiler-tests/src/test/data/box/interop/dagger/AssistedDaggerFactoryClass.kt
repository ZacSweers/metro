// ENABLE_DAGGER_KSP

// Anvil may generate objects

// MODULE: lib
// FILE: ExampleClass.java
package test;

import javax.inject.Inject;
import javax.inject.Provider;
import dagger.Lazy;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import dagger.assisted.AssistedFactory;

public class ExampleClass {
  @AssistedInject
  public ExampleClass(@Assisted int intValue) {}
  @AssistedFactory
  public interface Factory {
    ExampleClass create(int intValue);
  }
}

// MODULE: main(lib)
package test

@DependencyGraph
interface ExampleGraph {
  val exampleClassFactory: ExampleClass.Factory
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val factory = graph.exampleClassFactory
  assertNotNull(factory.create(1))
  return "OK"
}
