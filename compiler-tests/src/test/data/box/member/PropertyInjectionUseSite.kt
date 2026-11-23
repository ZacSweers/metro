class Target {
  @set:Inject var viaSetter = ""
    set(value) { field = value.uppercase() }

  @field:Inject var viaField = ""
    set(value) { error("Unexpected setter call with $value") }

  private var stored = ""
  @set:Inject var computed: String
    get() = stored
    set(value) { stored = value.uppercase() }
}

@DependencyGraph
interface Graph {
  fun inject(target: Target)
  @Provides fun value(): String = "metro"
}

fun box(): String {
  val target = Target()
  createGraph<Graph>().inject(target)
  assertEquals("METRO", target.viaSetter)
  assertEquals("metro", target.viaField)
  assertEquals("METRO", target.computed)
  return "OK"
}
