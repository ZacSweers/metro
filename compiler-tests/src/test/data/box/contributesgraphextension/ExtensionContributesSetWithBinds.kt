object AppScope
object LoggedInScope

interface Task
@Inject class TaskImpl1 : Task
@Inject class TaskImpl2 : Task

@DependencyGraph(AppScope::class)
interface ExampleGraph

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val tasks: Set<Task>
  @IntoSet @Binds val TaskImpl1.bind: Task
  @IntoSet @Binds val TaskImpl2.bind: Task

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory1 {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

fun box(): String {
  val tasks = createGraph<ExampleGraph>().createLoggedInGraph().tasks
  assertEquals(2, tasks.size)
  assertEquals(setOf(TaskImpl1::class, TaskImpl2::class), tasks.map { it::class }.toSet())
  return "OK"
}
