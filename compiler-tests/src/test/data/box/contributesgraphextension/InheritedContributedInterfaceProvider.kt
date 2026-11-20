// Regression test for https://github.com/ZacSweers/metro/issues/359

sealed interface TestScope
sealed interface TestChildScope

@ContributesTo(TestScope::class)
public interface TestContribution {
    @Provides
    public fun provideString(): String = ""
}

@SingleIn(TestScope::class)
@DependencyGraph(scope = TestScope::class)
interface ParentGraph

@GraphExtension(TestChildScope::class)
interface ChildGraph {
    val string: String

    @GraphExtension.Factory @ContributesTo(TestScope::class)
    interface Factory {
        fun createChildGraph(): ChildGraph
    }
}

fun box(): String {
  val child = createGraph<ParentGraph>().createChildGraph()
  assertEquals("", child.string)
  return "OK"
}
