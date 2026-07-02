// RUN_PIPELINE_TILL: BACKEND
// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
object Bindings {
  @Provides
  <!INLINABLE_PROVIDES_WARNING!>@SingleIn(AppScope::class)<!>
  fun provideInt(): Int = 3
}

@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph {
  val int: Int
}
