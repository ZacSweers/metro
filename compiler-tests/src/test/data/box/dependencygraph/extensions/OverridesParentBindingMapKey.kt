// Verifies that a child extension's `@Provides @IntoMap` contribution annotated with
// `@OverridesParentBinding` wins over an ancestor's contribution for the same map key in the
// child's merged view, while the parent's own view is unaffected.

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @Provides @IntoMap @StringKey("key") fun provideFromApp(): String = "parent"
  @Provides @IntoMap @StringKey("other") fun provideOther(): String = "shared"
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides
  @IntoMap
  @StringKey("key")
  @OverridesParentBinding
  fun provideFromChild(): String = "child"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val map: Map<String, String>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, String>
  val childGraph: ChildGraph
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  // Parent's view is unchanged.
  assertEquals(mapOf("key" to "parent", "other" to "shared"), appGraph.map)

  // Child's view replaces parent's `key` value with its own.
  assertEquals(mapOf("key" to "child", "other" to "shared"), appGraph.childGraph.map)
  return "OK"
}
