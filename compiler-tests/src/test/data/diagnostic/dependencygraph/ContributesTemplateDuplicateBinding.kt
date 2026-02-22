// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

interface Base

@ContributesTemplate
annotation class DuplicateContainer(val scope: KClass<*>) {
  companion object {
    @Provides fun <T : Base> contribute(target: T): Base = target
  }
}

@DuplicateContainer(AppScope::class) @Inject class Foo : Base

@DuplicateContainer(AppScope::class) @Inject class Bar : Base

@DependencyGraph(scope = AppScope::class)
interface <!METRO_ERROR!>TestGraph<!> {
  val base: Base
}
