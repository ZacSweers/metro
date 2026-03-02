// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

interface Base

class MissingHelper

@ContributesTemplate.Template
object MissingDepTemplate {
  @Provides @IntoSet fun <T : Base> contribute(target: T, helper: MissingHelper): Base = target
}

@ContributesTemplate(template = MissingDepTemplate::class)
annotation class MissingDepContainer(val scope: KClass<*>)

@MissingDepContainer(AppScope::class) @Inject class SomeTarget : Base

@DependencyGraph(scope = AppScope::class)
interface <!METRO_ERROR!>TestGraph<!> {
  val bases: Set<Base>
}
