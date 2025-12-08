@Inject class ValueHolder(val stringValue: String? = null)

@DependencyGraph
interface AppGraph {
  val holder: ValueHolder
}

fun box(): String {
  assertNull(createGraph<AppGraph>().holder.stringValue)
  return "OK"
}