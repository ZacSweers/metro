object AppScope

interface Task
@Inject class TaskImpl2 : Task
@Inject class TaskImpl1 : Task

@DependencyGraph(AppScope::class, isExtendable = true)
interface ExampleGraph {
  val tasks: Set<Task>
  @IntoSet @Binds val TaskImpl2.bind: Task
  @IntoSet @Binds val TaskImpl1.bind: Task
}

@ContributesGraphExtension(Unit::class)
interface LoggedInGraph {
  val tasksFromParent: Set<Task>

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory1 {
    fun createLoggedInGraph(): LoggedInGraph
  }
}