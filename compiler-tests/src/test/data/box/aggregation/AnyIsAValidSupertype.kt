@Inject @IntKey(3) @ContributesIntoMap(AppScope::class, binding = binding<Any>()) class Something

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Multibinds val ints: Map<Int, Any>
}

fun box(): String {
  assertTrue(createGraph<AppGraph>().ints[3] is Something)
  return "OK"
}
