// ENABLE_DAGGER_INTEROP
// WITH_ANVIL

import com.squareup.anvil.annotations.MergeComponent
import dagger.Module
import dagger.Subcomponent

abstract class SandboxedScope

abstract class SandboxedActivityScope

@Module object FormPreviewModule

@Subcomponent(modules = [FormPreviewModule::class])
interface FormPreviewActivityComponent {
  @Subcomponent.Factory
  fun interface Factory {
    fun createFormPreviewActivityComponent(): FormPreviewActivityComponent
  }
}

@MergeComponent(scope = SandboxedScope::class)
interface SandboxedComponent {
  fun formPreviewActivityComponentFactory(): FormPreviewActivityComponent.Factory
}

fun box(): String {
  val graph = createGraph<SandboxedComponent>()
  val factory = graph.formPreviewActivityComponentFactory()
  val component = factory.createFormPreviewActivityComponent()
  return "OK"
}
