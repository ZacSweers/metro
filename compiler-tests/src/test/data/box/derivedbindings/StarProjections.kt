// A star in a generic provider's return type matches a star in the requested key. A type variable
// can bind a type that has a star inside it, but it never binds a star itself.
interface Labeled {
  val label: String
}

interface IThing<A, B> : Labeled

class Thing<A, B>(override val label: String) : IThing<A, B>

interface Encoder<T> : Labeled

class NamedEncoder<T>(override val label: String) : Encoder<T>

@BindingContainer
object Bindings {
  @Provides fun <T> thing(): Thing<T, *> = Thing<T, Int>("thing")

  @Provides fun mapEncoder(): Encoder<Map<String, *>> = NamedEncoder("map")

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("list(${element.label})")

  // A catch-all for keys that nothing else matches, like `Encoder<List<*>>`.
  @Provides inline fun <reified T> any(): Encoder<T> = NamedEncoder("any")
}

@BindingContainer
interface ThingBindings {
  @Binds fun <T> Thing<T, *>.asIThing(): IThing<T, *>
}

@DependencyGraph(bindingContainers = [Bindings::class, ThingBindings::class])
interface AppGraph {
  // `thing` matches with `T = String`.
  val thing: Thing<String, *>

  // `asIThing` aliases this to `Thing<String, *>`.
  val iThing: IThing<String, *>

  // `list` matches with `T = Map<String, *>`.
  val listOfMaps: Encoder<List<Map<String, *>>>

  // `list` can't bind `T` to a star, so the catch-all supplies this with `T = List<*>`.
  val listOfStars: Encoder<List<*>>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("thing", graph.thing.label)
  assertEquals("thing", graph.iThing.label)
  assertEquals("list(map)", graph.listOfMaps.label)
  assertEquals("any", graph.listOfStars.label)
  return "OK"
}
