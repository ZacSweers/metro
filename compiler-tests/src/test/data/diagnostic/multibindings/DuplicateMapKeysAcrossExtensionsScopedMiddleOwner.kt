// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A scoped entry owned by a middle graph extension and a grandchild entry with the same map key
// report a duplicate map key in the grandchild.

abstract class ChildScope private constructor()

abstract class GrandchildScope private constructor()

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  <!INLINABLE_PROVIDES_WARNING!>@SingleIn(ChildScope::class)<!>
  @Provides
  @IntoMap
  @StringKey("key")
  fun provideChildEntry(): String = "child"
}

@BindingContainer
@ContributesTo(GrandchildScope::class)
object GrandchildProviders {
  @Provides @IntoMap @StringKey("key") fun provideGrandchildEntry(): String = "grandchild"
}

@GraphExtension(GrandchildScope::class)
interface GrandchildGraph {
  val <!DUPLICATE_MAP_KEY!>map<!>: Map<String, String>
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val map: Map<String, String>
  val grandchildGraph: GrandchildGraph
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childGraph: ChildGraph
}
