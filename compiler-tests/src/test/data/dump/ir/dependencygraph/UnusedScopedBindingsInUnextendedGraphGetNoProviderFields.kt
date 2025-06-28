@SingleIn(AppScope::class)
@DependencyGraph
interface AppGraph {
  @Provides @SingleIn(AppScope::class) fun provideString(): String = "Hi"
  @Provides private fun provideInt(): Int = 3

  val int: Int
}