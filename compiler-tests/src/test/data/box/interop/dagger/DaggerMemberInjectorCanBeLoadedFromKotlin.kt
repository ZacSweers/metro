// MODULE: lib
// ENABLE_ANVIL_KSP
// DISABLE_METRO

// FILE: Dependency.java
public interface Dependency {
}

// FILE: ExampleClass.kt
import javax.inject.Inject

class ExampleClass {
  @Inject
  lateinit var dependency: Dependency
  lateinit var setterDep: Dependency
  lateinit var setterDep2: Dependency
  lateinit var setterDep3: String

  // Setter injection
  @Inject
  fun setterInject(dep: Dependency) {
    this.setterDep = dep
  }

  // Setter injection
  @Inject
  fun setterInject2(dep: Dependency, stringDep: String) {
    this.setterDep2 = dep
    this.setterDep3 = stringDep
  }
}

// MODULE: main(lib)
// WITH_ANVIL
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
  // TODO anvil (and anvil-ksp) don't support setter injection
//  assertNotNull(example.setterDep)
//  assertNotNull(example.setterDep2)
//  assertEquals("Hello", example.setterDep3)
  return "OK"
}