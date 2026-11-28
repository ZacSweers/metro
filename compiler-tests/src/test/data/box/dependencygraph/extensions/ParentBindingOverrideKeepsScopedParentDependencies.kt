// A scoped parent binding keeps the parent's dependencies. The child overrides Logger, and the
// Repo it requests is still the parent's instance with the parent's Logger.

abstract class ChildScope private constructor()

class Logger(val name: String)

@SingleIn(AppScope::class) @Inject class Repo(val logger: Logger)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childGraph: ChildGraph

  @Provides fun provideLogger(): Logger = Logger("app")
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val logger: Logger
  val repo: Repo

  @Provides fun provideLogger(): Logger = Logger("child")
}

fun box(): String {
  val child = createGraph<AppGraph>().childGraph
  assertEquals("child", child.logger.name)
  assertEquals("app", child.repo.logger.name)
  return "OK"
}
