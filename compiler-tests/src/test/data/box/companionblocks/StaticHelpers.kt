// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK

@Inject
class GenericValue<T> private constructor(val value: T, val values: List<T>)

@Inject
class SingletonValue

@Inject
class MemberValue private constructor(val value: String) {
  @Inject lateinit var values: List<String>

  var number: Int = 0

  @Inject
  fun injectNumber(numberToInject: Int) {
    number = numberToInject
  }
}

@DependencyGraph
interface AppGraph {
  val generic: GenericValue<String>
  val singleton: SingletonValue
  val member: MemberValue
  val membersInjector: MembersInjector<MemberValue>

  @Provides fun value(): String = "static"
  @Provides fun values(): List<String> = listOf("static")
  @Provides fun number(): Int = 42
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("static", graph.generic.value)
  assertEquals(listOf("static"), graph.generic.values)
  assertNotNull(graph.singleton)
  assertEquals("static", graph.member.value)
  assertEquals(listOf("static"), graph.member.values)
  assertEquals(42, graph.member.number)
  graph.membersInjector.injectMembers(graph.member)
  assertEquals(42, graph.member.number)
  return "OK"
}
