// FUNCTION_INJECT_ANNOTATIONS_OVERRIDE: ""
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// CHECK_BYTECODE_TEXT

package test

@Inject
fun ignored(): String = "unused"

@Inject
class Message

@DependencyGraph
interface ExampleGraph {
  val message: Message
}

fun box(): String {
  assertIs<Message>(createGraph<ExampleGraph>().message)
  return "OK"
}

// 0 class test/Ignored\b
