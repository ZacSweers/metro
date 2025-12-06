@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  val int1: Provider<Int>
  val int2: Provider<Int>

  @Provides fun provideLong(): Long = 3L

  val long1: Lazy<Long>
  val long2: Lazy<Long>
}