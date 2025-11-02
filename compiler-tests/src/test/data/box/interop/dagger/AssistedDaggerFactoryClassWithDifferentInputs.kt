// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: ExampleClass.java
package test;

import javax.inject.Inject;
import javax.inject.Provider;
import dagger.Lazy;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import dagger.assisted.AssistedFactory;

public class ExampleClass {
  @AssistedInject public ExampleClass(
  @Assisted int intValue,
  String value,
  Provider<String> provider,
  Lazy<String> lazy
  ) {
  }
  @AssistedFactory
  public interface Factory {
    ExampleClass create(int intValue);
  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface ExampleGraph {
  val exampleClassFactory: ExampleClass.Factory

  @Provides fun provideString(): String = "hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val factory = graph.exampleClassFactory
  assertNotNull(factory.create(1))
  return "OK"
}
