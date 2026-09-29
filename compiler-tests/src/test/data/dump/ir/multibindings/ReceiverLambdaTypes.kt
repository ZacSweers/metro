// DUMP_IR

@DependencyGraph
interface ExampleGraph {
  val elements: Set<String>
  val entries: Map<Int, String>

  @Provides @IntoSet fun firstElement(): String = "first"
  @Provides @IntoSet fun secondElement(): String = "second"

  @Provides @IntoMap @IntKey(1) fun firstEntry(): String = "first"
  @Provides @IntoMap @IntKey(2) fun secondEntry(): String = "second"
}
