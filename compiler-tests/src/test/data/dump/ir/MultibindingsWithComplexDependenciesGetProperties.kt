@DependencyGraph
interface AppGraph {
  val ints: Set<Int>
  val consumer: IntsConsumer

  @Provides fun provideString(): String = "3"
  @Provides @IntoSet fun provideInt(string: String): Int = string.toInt()
}

@Inject class IntsConsumer(val ints: Set<Int>)
