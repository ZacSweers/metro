@HasMemberInjections
sealed class Base<T : Any> {
    @Inject private lateinit var formatter: Formatter

    fun doSomething(t: T): String {
        return formatter.format("${javaClass.simpleName} is doing something with ${formatter.format(t)}")
    }
}

class IntChild : Base<Int>()

class StringChild : Base<String>()

@Inject
class Formatter {
    fun format(any: Any) = any.toString()
}

// Graph definition
@DependencyGraph
interface AppGraph {
    fun inject(base: Base<*>)
}

fun box(): String {
    val graph = createGraph<AppGraph>()

    val intChild = IntChild()
    graph.inject(intChild)
    assertEquals("IntChild is doing something with 5", intChild.doSomething(5))

    val stringChild = StringChild()
    graph.inject(stringChild)
    assertEquals("StringChild is doing something with five", stringChild.doSomething("five"))

    return "OK"
}