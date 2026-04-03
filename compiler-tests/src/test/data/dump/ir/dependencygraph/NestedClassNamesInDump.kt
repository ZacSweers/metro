@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val string: String
  val charSequence: CharSequence

  @ContributesTo(AppScope::class)
  @BindingContainer
  interface Bindings {
    @Binds val String.bind: CharSequence

    companion object {
      @SingleIn(AppScope::class)
      @Provides
      fun provideString(): String = "Hello"
    }
  }
}