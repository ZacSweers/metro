// REPORTS_DESTINATION: metro/reports
// ENABLE_DAGGER_INTEROP
// METRO_JVM_ONLY

import dagger.BindsOptionalOf
import java.util.Optional

@Inject class Value(val origin: String = "implicit")

@SingleIn(AppScope::class) @Inject class ParentValue

@Inject class FirstParentConsumer(val parentValue: ParentValue)

@Inject class SecondParentConsumer(val parentValue: ParentValue)

@Inject
class MemberTarget {
  @Inject lateinit var value: Value
}

@BindingContainer
interface OptionalBindings {
  @BindsOptionalOf fun optionalValue(): Value
}

@GraphExtension
interface ChildGraph {
  val parentValue: ParentValue
  val first: FirstParentConsumer
  val second: SecondParentConsumer
}

@DependencyGraph(AppScope::class, bindingContainers = [OptionalBindings::class])
interface AppGraph {
  val value: Value
  val values: Set<Value>
  val optionalValue: Optional<Value>
  val memberTarget: MemberTarget
  val parentValue: ParentValue
  val child: ChildGraph

  @Provides fun value(): Value = Value("explicit")

  @Provides @IntoSet fun element(value: Value): Value = value
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("explicit", graph.value.origin)
  assertEquals("explicit", graph.values.single().origin)
  assertEquals("explicit", graph.optionalValue.get().origin)
  assertEquals("explicit", graph.memberTarget.value.origin)
  val child = graph.child
  assertSame(graph.parentValue, child.parentValue)
  assertSame(graph.parentValue, child.first.parentValue)
  assertSame(graph.parentValue, child.second.parentValue)
  return "OK"
}
