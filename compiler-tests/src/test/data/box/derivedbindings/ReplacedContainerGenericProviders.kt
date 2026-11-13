// ENABLE_DERIVED_BINDINGS

// A replaced or excluded contributed container takes its generic providers with it.
interface Encoder<T> {
  val name: String
}

class NamedEncoder<T>(override val name: String) : Encoder<T>

@ContributesTo(AppScope::class)
@BindingContainer
object OriginalEncoders {
  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("original(${element.name})")
}

// This would be ambiguous with OriginalEncoders.list if the replacement didn't apply.
@ContributesTo(AppScope::class, replaces = [OriginalEncoders::class])
@BindingContainer
object ReplacementEncoders {
  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = NamedEncoder("replacement(${element.name})")
}

@ContributesTo(AppScope::class)
@BindingContainer
object ExcludedEncoders {
  @Provides
  fun <T> set(element: Encoder<T>): Encoder<Set<T>> = NamedEncoder("excluded(${element.name})")
}

@DependencyGraph(AppScope::class, excludes = [ExcludedEncoders::class])
interface AppGraph {
  val stringList: Encoder<List<String>>
  val stringSet: Encoder<Set<String>>

  @Provides fun string(): Encoder<String> = NamedEncoder("string")

  // This would be ambiguous with ExcludedEncoders.set if the exclusion didn't apply.
  @Provides
  fun <T> set(element: Encoder<T>): Encoder<Set<T>> = NamedEncoder("graph(${element.name})")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("replacement(string)", graph.stringList.name)
  assertEquals("graph(string)", graph.stringSet.name)
  return "OK"
}
