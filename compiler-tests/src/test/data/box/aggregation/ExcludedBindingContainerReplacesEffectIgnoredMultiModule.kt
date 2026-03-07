// When a binding container from another module is excluded, its replaces annotation should have no effect.

// MODULE: original
// Contains the original binding container that provides Int = 1
@ContributesTo(AppScope::class)
@BindingContainer
object OriginalIntBinding {
  @Provides fun provideInt(): Int = 1
}

// MODULE: replacement(original)
// Contains a replacement binding container that would provide Int = 2
// This module depends on 'original' so it can reference OriginalIntBinding in replaces
@ContributesTo(AppScope::class, replaces = [OriginalIntBinding::class])
@BindingContainer
object ReplacementIntBinding {
  @Provides fun provideInt(): Int = 2
}

// MODULE: main(original, replacement)
// The graph excludes ReplacementIntBinding, so OriginalIntBinding should still be used
@DependencyGraph(AppScope::class, excludes = [ReplacementIntBinding::class])
interface AppGraph {
  val int: Int
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  // ReplacementIntBinding is excluded, so its replaces=[OriginalIntBinding] should have no effect
  // OriginalIntBinding should still contribute, providing Int = 1
  assertEquals(1, graph.int)
  return "OK"
}
