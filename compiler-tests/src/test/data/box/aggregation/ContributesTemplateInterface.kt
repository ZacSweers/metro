import kotlin.reflect.KClass

@ContributesTemplate
annotation class InterfaceContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet inline fun <reified T : Any> contributeToSet(): String = T::class.simpleName!!
  }
}

@InterfaceContainer(AppScope::class)
interface MyInterface

@DependencyGraph(AppScope::class)
interface AppGraph {
  val names: Set<String>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(setOf("MyInterface"), graph.names)
  return "OK"
}
