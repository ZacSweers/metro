// ENABLE_DAGGER_INTEROP
// MODULE: lib
import dagger.BindsOptionalOf
import dagger.Module

@Module
interface ExternalBindings {
  @BindsOptionalOf
  fun optionalLong(): Long

  @Named("qualified")
  @BindsOptionalOf
  fun qualifiedOptionalLong(): Long
}

// MODULE: main(lib)
import javax.inject.Inject
import dagger.BindsOptionalOf
import dagger.Component
import dagger.Module
import java.util.Optional

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

@Component(modules = [Bindings::class, ExternalBindings::class])
interface ExampleGraph {
  val emptyOptionalAccessor: Optional<String>
  @Named("qualified") val qualifiedEmptyOptionalAccessor: Optional<String>
  val presentOptionalAccessor: Optional<Int>
  @Named("qualified") val qualifiedEmptyIntAccessor: Optional<Int>
  val presentOptionalLongAccessor: Optional<Long>
  @Named("qualified") val qualifiedEmptyLongAccessor: Optional<Long>
  val stringConsumer: StringConsumer

  @Provides fun provideInt(): Int = 3
  @Provides fun provideLong(): Long = 3L
}

class StringConsumer @Inject constructor(
  val value: Optional<String>
)

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertTrue(graph.emptyOptionalAccessor.isEmpty())
  assertTrue(graph.qualifiedEmptyOptionalAccessor.isEmpty())
  assertEquals(3, graph.presentOptionalAccessor.get())
  assertTrue(graph.qualifiedEmptyIntAccessor.isEmpty())
  assertEquals(3L, graph.presentOptionalLongAccessor.get())
  assertTrue(graph.qualifiedEmptyLongAccessor.isEmpty())
  assertTrue(graph.stringConsumer.value.isEmpty())
  return "OK"
}
