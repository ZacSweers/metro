var defaultValue = "initial"

fun defaultMessage(): String = "function"

@Inject
class ExampleClass(
  val lambda: () -> String = { "lambda" },
  val function: () -> String = ::defaultMessage,
  val property: kotlin.reflect.KMutableProperty0<String> = ::defaultValue,
)

@DependencyGraph
interface ExampleGraph {
  val example: ExampleClass
}

fun box(): String {
  val example = createGraph<ExampleGraph>().example
  assertEquals("lambda", example.lambda())
  assertEquals("function", example.function())
  assertEquals("initial", example.property.get())
  example.property.set("updated")
  assertEquals("updated", example.property.get())
  return "OK"
}
