// ENABLE_DAGGER_INTEROP
// https://github.com/ZacSweers/metro/pull/3031
import dagger.Component
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey

class Value

@Module
class Bindings {
  private val value = Value()

  @Provides fun value(): Value = value
  @Provides @IntoSet fun setValue(): Value = value
  @Provides @IntoMap @StringKey("value") fun mapValue(): Value = value
}

@Component(modules = [Bindings::class])
interface AppComponent {
  val value: Value
  val child: ChildComponent
}

@Subcomponent
interface ChildComponent {
  val value: Value
  val values: Set<Value>
  val valuesByKey: Map<String, Value>
  val grandchild: GrandchildComponent
}

@Subcomponent
interface GrandchildComponent {
  val value: Value
}

fun box(): String {
  val graph = createGraph<AppComponent>()
  val first = graph.child
  val second = graph.child
  assertSame(graph.value, first.value)
  assertSame(graph.value, second.value)
  assertSame(graph.value, first.grandchild.value)
  assertSame(graph.value, first.values.single())
  assertSame(graph.value, first.valuesByKey.getValue("value"))
  assertNotSame(first.values, first.values)
  assertNotSame(first.valuesByKey, first.valuesByKey)
  return "OK"
}
