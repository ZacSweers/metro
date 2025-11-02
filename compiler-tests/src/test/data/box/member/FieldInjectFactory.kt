class ExampleClass {
  @Inject lateinit var string: String
  @Inject lateinit var stringProvider: Provider<String>
  @Inject lateinit var stringListProvider: Provider<List<String>>
  @Inject lateinit var lazyString: Lazy<String>

  override fun equals(other: Any?): Boolean {
    return toString() == other.toString()
  }
  override fun toString(): String {
    return string + stringProvider() +
        stringListProvider()[0] + lazyString.value
  }
}

fun box(): String {
  @Suppress("DEPRECATION_ERROR")
  val injector: MembersInjector<ExampleClass> = ExampleClass.MetroMembersInjector.Companion.create(
    providerOf("a"),
    providerOf("b"),
    providerOf(listOf("c")),
    providerOf("d"),
  )

  val example = ExampleClass()
  injector.injectMembers(example)

  assertEquals(example.string, "a")
  assertEquals(example.stringProvider.invoke(), "b")
  assertEquals(example.stringListProvider.invoke(), listOf("c"))
  assertEquals(example.lazyString.value, "d")

  return "OK"
}
