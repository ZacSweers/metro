// MODULE: lib
// ENABLE_DAGGER_KSP

// FILE: Dependency.java
public interface Dependency {
}

// FILE: ExampleClass.java
import javax.inject.Inject;
import javax.inject.Named;

public class ExampleClass {
  @Inject public Dependency dependency;
  @Inject @Named("qualified") public Dependency qualified;
  Dependency setterDep;
  Dependency setterDep2;
  String setterDep3;
  Dependency setterDepQualified;
  Dependency setterDep2Qualified;
  String setterDep3Qualified;

  // Setter injection
  @Inject public void setterInject(Dependency dep) {
    this.setterDep = dep;
  }

  @Inject public void setterInject2(Dependency dep, String stringDep) {
    this.setterDep2 = dep;
    this.setterDep3 = stringDep;
  }

  // Setters with qualifiers
  @Inject public void setterInjectQualified(@Named("qualified") Dependency dep) {
    this.setterDepQualified = dep;
  }

  @Inject public void setterInject2Qualified(@Named("qualified") Dependency dep, @Named("qualified") String stringDep) {
    this.setterDep2Qualified = dep;
    this.setterDep3Qualified = stringDep;
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
  @Provides @javax.inject.Named("qualified") fun provideQualifiedString(): String = "Hello qualified"
  @Binds @javax.inject.Named("qualified") fun Dependency.bind(): Dependency
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val example = ExampleClass()

  graph.inject(example)
  assertNotNull(example.dependency)
  assertNotNull(example.setterDep)
  assertNotNull(example.setterDep2)
  assertEquals("Hello", example.setterDep3)
  assertNotNull(example.qualified)
  assertNotNull(example.setterDepQualified)
  assertNotNull(example.setterDep2Qualified)
  assertEquals("Hello qualified", example.setterDep3Qualified)
  return "OK"
}