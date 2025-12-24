// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// https://github.com/ZacSweers/metro/issues/1590

@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!METRO_ERROR!>multibindings<!>: Map<String, String>
}

@BindingContainer
@ContributesTo(AppScope::class)
object Providers {
  @Provides
  @IntoMap
  @StringKey("key")
  fun provideFirst(): String = "first"

  @Provides
  @IntoMap
  @StringKey("key")
  fun provideSecond(): String = "second"
}
