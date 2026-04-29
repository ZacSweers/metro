// https://github.com/ZacSweers/metro/issues/1999

// MODULE: lib
@SingleIn(String::class) @Inject class ValueHolder(val stringValue: String? = null)

// MODULE: main(lib)
@DependencyGraph(String::class)
interface AppGraph {
  val holder: ValueHolder
}

fun box(): String {
  assertNull(createGraph<AppGraph>().holder.stringValue)
  return "OK"
}
