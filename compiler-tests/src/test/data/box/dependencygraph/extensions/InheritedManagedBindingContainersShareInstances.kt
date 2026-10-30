// https://github.com/ZacSweers/metro/pull/3031
class Value

@BindingContainer
class Bindings {
  private val value = Value()

  @Provides fun value(): Value = value
  @Provides @IntoSet fun setValue(): Value = value
  @Provides @IntoMap @StringKey("value") fun mapValue(): Value = value
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val value: Value
  val child: ChildGraph
  val otherChild: OtherChildGraph
  val localChild: LocalChildGraph
  val suppliedChildFactory: SuppliedChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val value: Value
  val values: Set<Value>
  val valuesByKey: Map<String, Value>
  val grandchild: GrandchildGraph
}

@GraphExtension
interface OtherChildGraph {
  val value: Value
  val values: Set<Value>
  val valuesByKey: Map<String, Value>
}

@GraphExtension
interface GrandchildGraph {
  val value: Value
  val values: Set<Value>
  val valuesByKey: Map<String, Value>
}

@GraphExtension(bindingContainers = [Bindings::class])
interface LocalChildGraph {
  val value: Value
}

@GraphExtension
interface SuppliedChildGraph {
  val value: Value

  @GraphExtension.Factory
  interface Factory {
    fun create(@Includes bindings: Bindings): SuppliedChildGraph
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val child = graph.child
  val otherChild = graph.otherChild
  val grandchild = child.grandchild

  assertSame(graph.value, child.value)
  assertSame(graph.value, otherChild.value)
  assertSame(graph.value, grandchild.value)
  assertSame(graph.value, child.values.single())
  assertSame(graph.value, otherChild.values.single())
  assertSame(graph.value, grandchild.values.single())
  assertSame(graph.value, child.valuesByKey.getValue("value"))
  assertSame(graph.value, otherChild.valuesByKey.getValue("value"))
  assertSame(graph.value, grandchild.valuesByKey.getValue("value"))
  assertNotSame(child.values, child.values)
  assertNotSame(child.valuesByKey, child.valuesByKey)
  assertNotSame(graph.value, graph.localChild.value)
  assertNotSame(graph.value, createGraph<AppGraph>().value)

  val suppliedBindings = Bindings()
  val suppliedChild = graph.suppliedChildFactory.create(suppliedBindings)
  assertSame(suppliedBindings.value(), suppliedChild.value)
  assertNotSame(graph.value, suppliedChild.value)
  return "OK"
}
