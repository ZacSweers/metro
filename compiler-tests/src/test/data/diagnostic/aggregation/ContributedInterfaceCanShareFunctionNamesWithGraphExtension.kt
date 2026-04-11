// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

data class Dependency(val name: String)

@DependencyGraph(scope = AppScope::class) interface <!INCOMPATIBLE_OVERRIDES!>AppGraph<!>

@GraphExtension(scope = Unit::class)
interface MyGraph {

  fun dependency(): Dependency

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createMyGraph(): MyGraph
  }
}

@ContributesTo(Unit::class)
interface Bindings {
  @Provides @SingleIn(Unit::class) fun dependency(): Dependency = Dependency("1")
}
