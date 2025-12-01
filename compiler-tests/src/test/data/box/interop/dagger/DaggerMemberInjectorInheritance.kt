// https://github.com/ZacSweers/metro/issues/1447
// MODULE: lib
// ENABLE_DAGGER_KSP

// FILE: BaseClass.java
import jakarta.inject.Inject;

public class BaseClass {
  @Inject public String message;
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP

class SubClass : BaseClass() {
  @Inject internal lateinit var internalString: String
}

@DependencyGraph
interface ExampleGraph {
  fun inject(subClass: SubClass)

  @Provides fun provideMessage(): String = "Hello"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val subClass = SubClass()
  graph.inject(subClass)
  assertEquals("Hello", subClass.message)
  assertEquals("Hello", subClass.internalString)
  return "OK"
}