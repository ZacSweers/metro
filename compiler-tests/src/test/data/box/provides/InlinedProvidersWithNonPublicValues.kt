// Regression test for constant providers whose value references a non-public class, e.g.
// `fun provideSettings(): Settings = NoOpSettings` where `NoOpSettings` is file-private.
// These must not be inlined into consuming graphs, which may not be able to reference the
// class directly. Public objects provided as supertypes are still inlined.

// FILE: bindings.kt
import kotlin.reflect.KClass

interface SkipReceiptScreenSettings

private object NoOpSkipReceiptScreenSettings : SkipReceiptScreenSettings

interface Mode

private enum class PrivateMode : Mode {
  Entry
}

private class PrivateClassLiteral

object PublicSettings : SkipReceiptScreenSettings

@BindingContainer
object Bindings {
  @Provides
  fun provideSkipReceiptScreenSettings(): SkipReceiptScreenSettings =
    NoOpSkipReceiptScreenSettings

  @Provides fun provideMode(): Mode = PrivateMode.Entry

  @Provides fun provideClassLiteral(): KClass<*> = PrivateClassLiteral::class

  @Provides @Named("public") fun providePublicSettings(): SkipReceiptScreenSettings = PublicSettings
}

// FILE: main.kt
import kotlin.reflect.KClass

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val settings: SkipReceiptScreenSettings
  val settingsProvider: Provider<SkipReceiptScreenSettings>
  val settingsLazy: Lazy<SkipReceiptScreenSettings>
  val mode: Mode
  val classLiteral: KClass<*>
  @get:Named("public") val publicSettings: SkipReceiptScreenSettings
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val settings = graph.settings
  if (settings !== graph.settingsProvider()) return "Fail: provider accessor"
  if (settings !== graph.settingsLazy.value) return "Fail: lazy accessor"
  if (graph.mode.toString() != "Entry") return "Fail: enum accessor"
  if (graph.classLiteral.simpleName != "PrivateClassLiteral") return "Fail: class literal accessor"
  if (graph.publicSettings !== PublicSettings) return "Fail: public object accessor"
  return "OK"
}
