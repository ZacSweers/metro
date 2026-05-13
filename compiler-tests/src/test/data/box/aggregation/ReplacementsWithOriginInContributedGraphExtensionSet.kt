// Regression test for IR contribution merging in contributed graph extensions.
//
// Task2 replaces Task1 via @ContributesBinding(replaces = [Task1::class]). Task1 also contributes
// to a Set<Task> through a separate @ContributesTo binding container. The @Origin(Task1::class)
// annotation is the key link that tells Metro this generated contribution container belongs to
// Task1, so replacing Task1 must also remove Task1's set contribution in the extension graph.
abstract class LoggedInScope

interface Task

@Inject class Task1 : Task

@Inject class Task3 : Task

@ContributesTo(LoggedInScope::class)
@Origin(Task1::class)
interface Task1Contribution {
  @Binds @IntoSet fun bindTask1(task1: Task1): Task
}

@ContributesTo(LoggedInScope::class)
@Origin(Task3::class)
interface Task3Contribution {
  @Binds @IntoSet fun bindTask3(task3: Task3): Task
}

@Inject
@ContributesBinding(LoggedInScope::class, binding = binding<Task>(), replaces = [Task1::class])
class Task2 : Task

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val tasks: Set<Task>
  val task: Task

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>().createLoggedInGraph()

  assertEquals(1, graph.tasks.size)
  assertEquals("Task3", graph.tasks.single()::class.qualifiedName)
  assertEquals("Task2", graph.task::class.qualifiedName)
  return "OK"
}
