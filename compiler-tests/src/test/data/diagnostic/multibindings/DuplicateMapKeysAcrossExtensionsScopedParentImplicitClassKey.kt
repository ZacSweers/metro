// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A scoped parent entry and a child entry whose implicit class keys resolve to the same class
// report a duplicate map key in the child.

import kotlin.reflect.KClass

interface Base

@Inject class Impl : Base

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @SingleIn(AppScope::class)
  @Provides
  @IntoMap
  @ClassKey
  fun provideParentEntry(impl: Impl): Base = impl
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides @IntoMap @ClassKey fun provideChildEntry(impl: Impl): Base = impl
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val <!DUPLICATE_MAP_KEY!>map<!>: Map<KClass<*>, Base>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val map: Map<KClass<*>, Base>
  val childGraph: ChildGraph
}
