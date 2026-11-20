@DependencyGraph(AppScope::class)
interface ExampleGraph {
  val exampleClass: ExampleClass
}

@ContributesTo(AppScope::class)
interface IntsBinding {
  @Multibinds(allowEmpty = true)
  val ints: Map<Int, () -> Int>
}

fun interface IntHolder {
  fun value(): Int
}

@ContributesIntoMap(AppScope::class)
@IntKey(0)
@Inject
class ZeroHolder : IntHolder {
  override fun value(): Int = 0
}

@Inject class ExampleClass(val ints: Map<Int, () -> IntHolder>)

fun box(): String {
  val ints = createGraph<ExampleGraph>().exampleClass.ints
  assertEquals(mapOf(0 to 0), ints.mapValues { (_, provider) -> provider().value() })
  return "OK"
}
