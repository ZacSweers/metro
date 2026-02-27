interface UserProvider {
  fun getUser(): String
}

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  fun child(): ExampleChildGraph

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes userProvider: UserProvider): ExampleGraph
  }
}

@GraphExtension
interface ExampleChildGraph {
  fun repository(): ExampleRepository
}

@Inject @SingleIn(AppScope::class) class ExampleRepository(private val userProvider: UserProvider)

fun box(): String {
  val userProvider =
    object : UserProvider {
      override fun getUser(): String {
        return "user"
      }
    }
  val exampleGraph = createGraphFactory<ExampleGraph.Factory>().create(userProvider)
  val repository = exampleGraph.child().repository() // ClassCastException
  return "OK"
}
