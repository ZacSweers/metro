// ENABLE_SWITCHING_PROVIDERS: true

@SingleIn(AppScope::class)
@Inject
class Thing1

@SingleIn(AppScope::class)
@Inject
class Thing2

@SingleIn(AppScope::class)
@Inject
class Thing3

@DependencyGraph(AppScope::class)
interface AppGraph {
  val thing1: Thing1
  val thing2: Thing2
  val thing3: Thing3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.thing1)
  assertNotNull(graph.thing2)
  assertNotNull(graph.thing3)
  return "OK"
}

// <count> <instruction>
// Asserts that we use tableswitch and not fall-through equals checks
// CHECK_BYTECODE_TEXT
// 0 Intrinsics.areEqual
// 0 IF_ICMPNE
// 1 TABLESWITCH
// 1 ISTORE 1
// 1 GETFIELD AppGraph\$Impl\$SwitchingProvider\.id : I