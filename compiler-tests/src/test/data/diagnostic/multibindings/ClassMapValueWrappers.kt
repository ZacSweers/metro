// RUN_PIPELINE_TILL: FIR2IR
// ENABLE_KCLASS_TO_CLASS_INTEROP
// METRO_JVM_ONLY

import kotlin.reflect.KClass

@DependencyGraph
interface PlainValues {
  val plain: Map<Class<*>, String>
  val <!MISSING_BINDING!>wrapped<!>: Map<Class<*>, () -> String>
  @Provides fun values(): Map<KClass<*>, String> = emptyMap()
}

@DependencyGraph
interface WrappedValues {
  val wrapped: Map<Class<*>, () -> String>
  val <!MISSING_BINDING!>plain<!>: Map<Class<*>, String>
  @Provides fun values(): Map<KClass<*>, () -> String> = emptyMap()
}
