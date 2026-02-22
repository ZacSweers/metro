// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

interface Base

@ContributesTemplate.Template
object DuplicateTemplate {
  @Provides fun <T : Base> contribute(target: T): Base = target
}

@ContributesTemplate(template = DuplicateTemplate::class)
annotation class DuplicateContainer(val scope: KClass<*>)

@DuplicateContainer(AppScope::class) @Inject class Foo : Base

@DuplicateContainer(AppScope::class) @Inject class Bar : Base

@DependencyGraph(scope = AppScope::class)
interface <!METRO_ERROR!>TestGraph<!> {
  val base: Base
}
