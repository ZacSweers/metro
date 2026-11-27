// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A parent @Binds entry with a graph-private source and a child entry with the same map key report
// a duplicate map key in the child.

interface Base

class Impl : Base

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
interface AppBindings {
  @Binds @IntoMap @StringKey("key") fun bindParentEntry(impl: Impl): Base

  companion object {
    @GraphPrivate @Provides fun provideImpl(): Impl = Impl()
  }
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides @IntoMap @StringKey("key") fun provideChildEntry(): Base = object : Base {}
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val <!DUPLICATE_MAP_KEY!>map<!>: Map<String, Base>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<String, Base>
  val childGraph: ChildGraph
}
