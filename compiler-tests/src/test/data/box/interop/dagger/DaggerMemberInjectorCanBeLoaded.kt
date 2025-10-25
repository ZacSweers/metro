// MODULE: lib
// ENABLE_DAGGER_KSP

// FILE: Dependency.java
public interface Dependency {
}

// FILE: ExampleClass.java
import javax.inject.Inject;

public class ExampleClass {
  @Inject public Dependency dependency;
  Dependency setterDep = null;
  Dependency setterDep2 = null;
  String setterDep3 = null;

  // Setter injection
  @Inject public void setterInject(Dependency dep) {
    this.setterDep = dep;
  }

  // Setter injection
  @Inject public void setterInject2(Dependency dep, String stringDep) {
    this.setterDep2 = dep;
    this.setterDep3 = stringDep;
  }
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP

// FILE: DependencyImpl.kt
@ContributesBinding(AppScope::class)
class DependencyImpl @Inject constructor() : Dependency

// FILE: ExampleInjector.kt
@ContributesTo(AppScope::class)
interface ExampleInjector {
  fun inject(example: ExampleClass)
}

// FILE: ExampleGraph.kt
@DependencyGraph(AppScope::class)
interface ExampleGraph {
  @Provides fun provideString(): String = "Hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val example = ExampleClass()

  graph.inject(example)
  assertNotNull(example.dependency)
  assertNotNull(example.setterDep)
  assertNotNull(example.setterDep2)
  assertEquals("Hello", example.setterDep3)
  return "OK"
}