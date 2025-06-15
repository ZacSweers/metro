@SingleIn(AppScope::class)
@DependencyGraph(isExtendable = true)
interface AppGraph {
  // Unused in AppGraph but because it's extendable, it will get a field exposed anyway
  @Provides @SingleIn(AppScope::class) fun provideString(): String = "Hi"
  @Provides private fun provideInt(): Int = 3

  val int: Int
}