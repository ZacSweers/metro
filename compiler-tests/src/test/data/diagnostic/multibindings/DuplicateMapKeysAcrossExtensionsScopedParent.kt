// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A scoped parent entry and an unscoped child entry with the same map key report a duplicate map
// key in the child.

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  <!INLINABLE_PROVIDES_WARNING!>@SingleIn(AppScope::class)<!>
  @Provides
  @IntoMap
  @StringKey("key")
  fun provideParentEntry(): String = "parent"
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides @IntoMap @StringKey("key") fun provideChildEntry(): String = "child"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val <!DUPLICATE_MAP_KEY!>map<!>: Map<String, String>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, String>
  val childGraph: ChildGraph
}
