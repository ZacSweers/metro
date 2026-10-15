// ENABLE_DAGGER_INTEROP
import com.google.common.base.Optional
import dagger.BindsOptionalOf
import dagger.Component
import dagger.Module
import javax.inject.Inject

@Module
interface Bindings {
  @BindsOptionalOf
  fun optionalInt(): Int

  @BindsOptionalOf
  fun optionalString(): String
}

class Consumer @Inject constructor(
  val present: Optional<Int>,
  val absent: Optional<String>
)

@Component(modules = [Bindings::class])
interface ExampleGraph {
  val present: Optional<Int>
  val absent: Optional<String>
  val consumer: Consumer

  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  if (!graph.present.isPresent || graph.present.get() != 3) return "FAIL: present"
  if (graph.absent.isPresent) return "FAIL: absent"
  if (!graph.consumer.present.isPresent) return "FAIL: consumer.present"
  if (graph.consumer.absent.isPresent) return "FAIL: consumer.absent"
  return "OK"
}
