@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3
  @Provides fun provideString(int: Int): String = int.toString()

  val scalarString: String
  val scalarString2: String
  val providerString: Provider<String>
}