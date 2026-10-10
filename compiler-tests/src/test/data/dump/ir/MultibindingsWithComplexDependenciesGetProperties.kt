@DependencyGraph
interface AppGraph {
  val ints: Set<Int>
  val ints2: Set<Int>

  @Provides fun provideString(): String = "3"
  @Provides @IntoSet fun provideInt(string: String): Int = string.toInt()
}