// ENABLE_DAGGER_INTEROP
import javax.inject.Inject
import dagger.BindsOptionalOf
import dagger.Component
import dagger.Module
import java.util.Optional

// TODO qualifiers, multi-module

@Module
interface Bindings {
  @BindsOptionalOf
  fun optionalString(): String

  @BindsOptionalOf
  fun optionalInt(): Int
}

@Component(modules = [Bindings::class])
interface ExampleGraph {
  val emptyOptionalAccessor: Optional<String>
  val presentOptionalAccessor: Optional<Int>
  val stringConsumer: StringConsumer

  @Provides fun provideInt(): Int = 3
}

class StringConsumer @Inject constructor(
  val value: Optional<String>
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertTrue(graph.emptyOptionalAccessor.isEmpty())
  assertEquals(3, graph.presentOptionalAccessor.get())
  assertTrue(graph.stringConsumer.value.isEmpty())
  return "OK"
}
