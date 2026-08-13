// MIN_COMPILER_VERSION: 2.4.20
// ENABLE_DAGGER_INTEROP
// GENERATE_CLASSES_IN_IR: true

class OuterClassWithADeliberatelyLongNameToOverflowMetrosLimit {
  class MiddleClassWithADeliberatelyLongNameToOverflowMetrosLimit {
    class InnerClassWithADeliberatelyLongNameToOverflowMetrosLimit {
      class NestedClassWithADeliberatelyLongNameToOverflowMetrosLimit
    }
  }
}

@DependencyGraph
interface AppGraph {
  val int: Int

  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.int)
  return "OK"
}
