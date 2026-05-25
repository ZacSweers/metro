// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

abstract class ChildScope private constructor()

@BindingContainer
@ContributesTo(AppScope::class)
object AppProviders {
  @Provides
  @IntoMap
  @StringKey("key")
  fun provideFromApp(): String = "first"
}

@BindingContainer
@ContributesTo(ChildScope::class)
object ChildProviders {
  @Provides
  @IntoMap
  @StringKey("key")
  fun provideFromChild(): String = "second"
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
