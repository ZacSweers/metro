// MODULE: lib
// ENABLE_DAGGER_KSP
// FILE: GenericModule.java
package test;

import dagger.Module;
import dagger.Provides;

@Module
public abstract class GenericModule<T> {
  private final T value;

  public GenericModule(T value) {
    this.value = value;
  }

  @Provides
  public String provideString() {
    return value.toString();
  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes module: GenericModule<Int>): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create(object : GenericModule<Int>(42) {})
  assertEquals("42", graph.string)
  return "OK"
}
