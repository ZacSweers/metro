@DependencyGraph
interface AppGraph {
  @Provides @IntoSet fun provideInt(string: String): Int = string.toInt()
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return "OK"
}