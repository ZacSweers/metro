// ENABLE_DERIVED_BINDINGS

// A dynamic generic provider also replaces the bindings a graph extension declares.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@BindingContainer
object RealEncoders {
  @Provides fun string(): Encoder<String> = NamedEncoder("real string")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("real list(${element.name})")
}

@GraphExtension(bindingContainers = [RealEncoders::class])
interface ChildGraph {
  val string: Encoder<String>
  val strings: Encoder<List<String>>
}

@DependencyGraph
interface AppGraph {
  val child: ChildGraph
}

@BindingContainer
object FakeEncoders {
  @Provides fun <T> fake(): Encoder<T> = NamedEncoder("fake")
}

fun box(): String {
  val real = createGraph<AppGraph>().child
  assertEquals("real string", real.string.name)
  assertEquals("real list(real string)", real.strings.name)

  val fake = createDynamicGraph<AppGraph>(FakeEncoders).child
  assertEquals("fake", fake.string.name)
  assertEquals("fake", fake.strings.name)
  return "OK"
}
