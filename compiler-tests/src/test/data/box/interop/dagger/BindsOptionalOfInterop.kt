// ENABLE_DAGGER_INTEROP
import javax.inject.Inject
import dagger.BindsOptionalOf
import dagger.Component
import dagger.Module
import java.util.Optional

// TODO multi-module

@Module
interface Bindings {
  @BindsOptionalOf
  fun optionalString(): String

  @Named("qualified")
  @BindsOptionalOf
  fun qualifiedOptionalString(): String

  @BindsOptionalOf
  fun optionalInt(): Int

  @Named("qualified")
  @BindsOptionalOf
  fun qualifiedOptionalInt(): Int
}

@Component(modules = [Bindings::class])
interface ExampleGraph {
  val emptyOptionalAccessor: Optional<String>
  @Named("qualified") val qualifiedEmptyOptionalAccessor: Optional<String>
  val presentOptionalAccessor: Optional<Int>
  @Named("qualified") val qualifiedPresentOptionalAccessor: Optional<Int>
  val stringConsumer: StringConsumer

  @Provides fun provideInt(): Int = 3
}

class StringConsumer @Inject constructor(
  val value: Optional<String>
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertTrue(graph.emptyOptionalAccessor.isEmpty())
  assertTrue(graph.qualifiedEmptyOptionalAccessor.isEmpty())
  assertEquals(3, graph.presentOptionalAccessor.get())
  assertTrue(graph.qualifiedPresentOptionalAccessor.isEmpty())
  assertTrue(graph.stringConsumer.value.isEmpty())
  return "OK"
}
