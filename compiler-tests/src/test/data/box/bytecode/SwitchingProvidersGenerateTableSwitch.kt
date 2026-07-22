// ENABLE_SWITCHING_PROVIDERS: true
// STATEMENTS_PER_INIT_FUN: 2

@SingleIn(AppScope::class)
@Inject
class Thing1

@SingleIn(AppScope::class)
@Inject
class Thing2

@SingleIn(AppScope::class)
@Inject
class Thing3

@SingleIn(AppScope::class)
@Inject
class Thing4

@SingleIn(AppScope::class)
@Inject
class Thing5

@SingleIn(AppScope::class)
@Inject
class Thing6

@DependencyGraph(AppScope::class)
interface AppGraph {
  val thing1: Thing1
  val thing2: Thing2
  val thing3: Thing3
  val thing4: Thing4
  val thing5: Thing5
  val thing6: Thing6
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.thing1)
  assertNotNull(graph.thing2)
  assertNotNull(graph.thing3)
  assertNotNull(graph.thing4)
  assertNotNull(graph.thing5)
  assertNotNull(graph.thing6)
  return "OK"
}

// <count> <instruction>
// Asserts that invoke() routes directly to one helper and all switches lower to tableswitch.
// CHECK_BYTECODE_TEXT
// @AppGraph$Impl$SwitchingProvider.class:
// 0 Intrinsics.areEqual
// 0 IF_ICMPNE
// 1 IDIV
// 4 TABLESWITCH
