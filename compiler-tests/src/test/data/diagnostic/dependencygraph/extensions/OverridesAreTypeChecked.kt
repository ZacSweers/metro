// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// https://github.com/ZacSweers/metro/issues/904

abstract class ChildScope private constructor()

@GraphExtension(ChildScope::class)
interface ChildGraph {

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createChild(): ChildGraph
  }
}

@ContributesTo(ChildScope::class)
interface StringProvider {
  val fooProperty: String
  fun fooFunction(): String

  @Provides
  fun provideString(): String = "test"
}

@ContributesTo(ChildScope::class)
interface IntProvider {
  val fooProperty: Int
  fun fooFunction(): Int

  @Provides
  fun provideInt(): Int = 1
}

@DependencyGraph(AppScope::class)
interface <!METRO_ERROR, METRO_ERROR!>AppGraph<!>
