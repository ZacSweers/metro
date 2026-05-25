// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// PARENT_BINDING_OVERRIDE_BEHAVIOR: REQUIRE_ANNOTATION

abstract class ChildScope private constructor()

@DependencyGraph(AppScope::class)
interface AppGraph {
  val message: String

  @Provides fun provideMessage(): String = "parent"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val childMessage: String

  @Provides fun provideMessage(): String = "child"

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}
