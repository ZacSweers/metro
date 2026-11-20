// FUNCTION_INJECT_ANNOTATIONS_OVERRIDE: test/InjectFunction
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// CHECK_BYTECODE_TEXT

package test

@Target(AnnotationTarget.FUNCTION)
annotation class InjectFunction

@Inject
class Message {
  @Inject lateinit var text: String
}

@InjectFunction
fun app(message: Message): String = message.text

@Inject
fun ignored(): String = "unused"

@DependencyGraph
interface ExampleGraph {
  val app: App

  @Provides fun message(): String = "Hello!"
}

fun box(): String {
  assertEquals("Hello!", createGraph<ExampleGraph>().app.invoke())
  return "OK"
}

// 0 class test/Ignored\b
