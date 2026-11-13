// Class lookup still runs when no generic provider matches the key.
@Inject class Repository<T>(val label: String)

@BindingContainer
object Repositories {
  @Provides fun <T : Number> numbers(): Repository<T> = Repository("provided")
}

@DependencyGraph(bindingContainers = [Repositories::class])
interface AppGraph {
  val ints: Repository<Int>
  val strings: Repository<String>

  @Provides fun label(): String = "injected"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("provided", graph.ints.label)
  assertEquals("injected", graph.strings.label)
  return "OK"
}
