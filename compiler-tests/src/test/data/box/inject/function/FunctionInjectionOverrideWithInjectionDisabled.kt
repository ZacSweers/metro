// FUNCTION_INJECT_ANNOTATIONS_OVERRIDE: test/InjectFunction
// CHECK_BYTECODE_TEXT

package test

@Target(AnnotationTarget.FUNCTION)
annotation class InjectFunction

@InjectFunction
fun String.customFunction(): String = this

@Inject
fun ordinaryFunction(): String = "unused"

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

// 0 class test/CustomFunction\b
// 0 class test/OrdinaryFunction\b
