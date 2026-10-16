// ENABLE_DAGGER_INTEROP
import dagger.BindsOptionalOf
import dagger.Component
import dagger.Module

@Module
interface Bindings {
  @BindsOptionalOf
  fun optionalInt(): Int
}

@Component(modules = [Bindings::class])
interface ExampleGraph {
  val javaOptional: java.util.Optional<Int>
  val guavaOptional: com.google.common.base.Optional<Int>

  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  if (graph.javaOptional.get() != 3) return "FAIL: java"
  if (graph.guavaOptional.get() != 3) return "FAIL: guava"
  return "OK"
}
