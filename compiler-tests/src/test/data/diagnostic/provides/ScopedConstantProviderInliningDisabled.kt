// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_PROVIDER_INLINING: false

@BindingContainer
object Bindings {
  @Provides
  @SingleIn(AppScope::class)
  fun provideInt(): Int = 3
}

@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph {
  val int: Int
}
