@DependencyGraph(AppScope::class, additionalScopes = [LoggedInScope::class])
interface ExampleGraph {
  val appClass: AppClass
  val loggedInClass: LoggedInClass
}

abstract class LoggedInScope private constructor()

@SingleIn(AppScope::class)
@Inject
class AppClass

@SingleIn(LoggedInScope::class)
@Inject
class LoggedInClass

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertNotNull(graph.appClass)
  assertNotNull(graph.loggedInClass)
  return "OK"
}
