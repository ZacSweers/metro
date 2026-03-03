import kotlin.reflect.KClass

@ContributesTemplate.Template
object InterfaceContainerTemplate {
  @Provides @IntoSet inline fun <reified T : Any> contributeToSet(): String = T::class.simpleName!!
}

@ContributesTemplate(template = InterfaceContainerTemplate::class)
annotation class InterfaceContainer(val scope: KClass<*>)

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
