// METRO_JVM_ONLY
// MIN_COMPILER_VERSION: 2.4.20
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// GENERATE_JVM_CONTRIBUTION_HINTS_IN_IR
// GENERATE_CLASSES_IN_IR: true
// MODULE: lib
package test

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class GenerateBindsContribution(val scope: KClass<*>)

interface ExternalValue

// The external provider emits a FIR hint for a generated @ContributesTo interface.
// The IR visitor encounters that interface too and must not emit a duplicate hint.
@Inject
@GenerateBindsContribution(AppScope::class)
class ExternalImpl : ExternalValue

interface NativeValue

@Inject
@ContributesBinding(AppScope::class)
class NativeImpl : NativeValue

// MODULE: main(lib)
package test

@DependencyGraph(AppScope::class)
interface AppGraph {
  val external: ExternalValue
  val native: NativeValue
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertIs<ExternalImpl>(graph.external)
  assertIs<NativeImpl>(graph.native)
  return "OK"
}
