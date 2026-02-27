// MODULE: user
@DependencyGraph
interface LoggedInGraph {
    fun getUser(): String

    @DependencyGraph.Factory
    interface Factory {
        fun create(@Provides user: String): LoggedInGraph
    }
}

// MODULE: example(user)
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface ExampleGraph {
    fun child(): ExampleChildGraph.Factory

    @DependencyGraph.Factory
    interface Factory {
        fun create(@Includes loggedInGraph: LoggedInGraph): ExampleGraph
    }
}

@GraphExtension
interface ExampleChildGraph {
    fun repository(): ExampleRepository

    @GraphExtension.Factory
    interface Factory {
        fun create(): ExampleChildGraph
    }
}

@Inject
class ExampleUseCase(private val loggedInGraph: LoggedInGraph)

@Inject
@SingleIn(AppScope::class)
class ExampleRepository(val useCase: ExampleUseCase)

// MODULE: main(user, example)
fun box(): String {
    val loggedInGraph = createGraphFactory<LoggedInGraph.Factory>().create("user")
    val exampleGraph = createGraphFactory<ExampleGraph.Factory>().create(loggedInGraph)
    val repository = exampleGraph.child().create().repository() // ClassCastException
    return "OK"
}
