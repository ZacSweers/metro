interface Value<T : Any> { val value: T }
interface Left0<T : Any> : Value<T>
interface Right0<T : Any> : Value<T>

// Each layer doubles the paths to Value.
interface Left1<T : Any> : Left0<T>, Right0<T>
interface Right1<T : Any> : Left0<T>, Right0<T>
interface Left2<T : Any> : Left1<T>, Right1<T>
interface Right2<T : Any> : Left1<T>, Right1<T>
interface Left3<T : Any> : Left2<T>, Right2<T>
interface Right3<T : Any> : Left2<T>, Right2<T>
interface Left4<T : Any> : Left3<T>, Right3<T>
interface Right4<T : Any> : Left3<T>, Right3<T>
interface Left5<T : Any> : Left4<T>, Right4<T>
interface Right5<T : Any> : Left4<T>, Right4<T>
interface Left6<T : Any> : Left5<T>, Right5<T>
interface Right6<T : Any> : Left5<T>, Right5<T>
interface Left7<T : Any> : Left6<T>, Right6<T>
interface Right7<T : Any> : Left6<T>, Right6<T>
interface Left8<T : Any> : Left7<T>, Right7<T>
interface Right8<T : Any> : Left7<T>, Right7<T>
interface Left9<T : Any> : Left8<T>, Right8<T>
interface Right9<T : Any> : Left8<T>, Right8<T>
interface Left10<T : Any> : Left9<T>, Right9<T>
interface Right10<T : Any> : Left9<T>, Right9<T>
interface Left11<T : Any> : Left10<T>, Right10<T>
interface Right11<T : Any> : Left10<T>, Right10<T>
interface Left12<T : Any> : Left11<T>, Right11<T>
interface Right12<T : Any> : Left11<T>, Right11<T>
interface Left13<T : Any> : Left12<T>, Right12<T>
interface Right13<T : Any> : Left12<T>, Right12<T>
interface Left14<T : Any> : Left13<T>, Right13<T>
interface Right14<T : Any> : Left13<T>, Right13<T>

@HasMemberInjections
abstract class Base<T : Any> {
  @Inject lateinit var inherited: T
}

@Inject
class Target<T : Any>(override val value: T) : Base<T>(), Left14<T>, Right14<T>

@DependencyGraph
interface DiamondGraph {
  val strings: Target<String>
  val ints: Target<Int>
  @Provides fun provideString(): String = "value"
  @Provides fun provideInt(): Int = 42
}

fun box(): String {
  val graph = createGraph<DiamondGraph>()
  val strings = graph.strings
  val ints = graph.ints
  assertEquals("value", strings.value)
  assertEquals("value", strings.inherited)
  assertEquals(42, ints.value)
  assertEquals(42, ints.inherited)
  return "OK"
}
