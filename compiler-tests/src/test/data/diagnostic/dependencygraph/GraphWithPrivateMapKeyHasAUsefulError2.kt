// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: common
import kotlin.reflect.KClass
import java.io.Closeable

@MapKey
annotation class ServiceKey(val value: KClass<out Closeable>)

// MODULE: feature(common)
import java.io.Closeable

@Inject
@ContributesIntoMap(AppScope::class)
@ServiceKey(TestClass::class)
class TestClass: Closeable {
  override fun close() {}
}

// MODULE: main(feature)
import kotlin.reflect.KClass
import java.io.Closeable

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Multibinds
  fun services(): Map<KClass<out Closeable>, Closeable>
}
