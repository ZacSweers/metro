// REPORTS_DESTINATION: metro/reports
// ENABLE_DAGGER_INTEROP
// METRO_JVM_ONLY

import dagger.BindsOptionalOf
import java.util.Optional

@Inject class Value(val origin: String = "implicit")

@SingleIn(AppScope::class) @Inject class ParentValue

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
  assertSame(graph.parentValue, graph.child.parentValue)
  return "OK"
}
