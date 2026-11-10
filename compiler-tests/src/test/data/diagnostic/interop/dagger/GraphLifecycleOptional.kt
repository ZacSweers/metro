// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP
// METRO_JVM_ONLY

import dagger.BindsOptionalOf
import java.util.Optional

@SingleIn(AppScope::class) @Inject class Scoped
@Inject class Unscoped
class Missing

@BindingContainer
interface Bindings {
  @BindsOptionalOf fun scoped(): Scoped
  @BindsOptionalOf fun unscoped(): Unscoped
  @BindsOptionalOf fun missing(): Missing
}

// An Optional is a new object each time, so the value it holds decides the warning.
@DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
interface AppGraph : CloseableGraph {
  @OnGraphInit
  fun start(
    scoped: Optional<Scoped>,
    <!UNSCOPED_LIFECYCLE_PARAMETER!>unscoped: Optional<Unscoped><!>,
    missing: Optional<Missing>,
  ) {}
}
