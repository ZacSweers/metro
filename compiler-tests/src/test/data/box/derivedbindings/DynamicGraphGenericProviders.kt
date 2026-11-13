// A dynamic generic provider replaces every binding it matches, exact or generic.
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

@DependencyGraph(bindingContainers = [RealEncoders::class])
interface AppGraph {
  val string: Encoder<String>
  val strings: Encoder<List<String>>
}

@BindingContainer
object FakeEncoders {
  @Provides fun <T> fake(): Encoder<T> = NamedEncoder("fake")
}

// An exact dynamic binding still wins over a dynamic generic provider.
@BindingContainer
object FakeEncodersWithExact {
  @Provides fun <T> fake(): Encoder<T> = NamedEncoder("fake")

  @Provides fun string(): Encoder<String> = NamedEncoder("fake string")
}

// A dynamic generic provider leaves the keys it doesn't match alone.
@BindingContainer
object FakeListEncoders {
  @Provides fun <T> fakeList(): Encoder<List<T>> = NamedEncoder("fake list")
}

// An exact dynamic binding replaces a binding that a generic provider would derive.
@BindingContainer
object FakeStringsEncoder {
  @Provides fun strings(): Encoder<List<String>> = NamedEncoder("fake strings")
}

fun box(): String {
  val real = createGraph<AppGraph>()
  assertEquals("real string", real.string.name)
  assertEquals("real list(real string)", real.strings.name)

  val fake = createDynamicGraph<AppGraph>(FakeEncoders)
  assertEquals("fake", fake.string.name)
  assertEquals("fake", fake.strings.name)

  val fakeWithExact = createDynamicGraph<AppGraph>(FakeEncodersWithExact)
  assertEquals("fake string", fakeWithExact.string.name)
  assertEquals("fake", fakeWithExact.strings.name)

  val fakeLists = createDynamicGraph<AppGraph>(FakeListEncoders)
  assertEquals("real string", fakeLists.string.name)
  assertEquals("fake list", fakeLists.strings.name)

  val fakeStrings = createDynamicGraph<AppGraph>(FakeStringsEncoder)
  assertEquals("real string", fakeStrings.string.name)
  assertEquals("fake strings", fakeStrings.strings.name)
  return "OK"
}
