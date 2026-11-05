// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: lib.kt
package test

import dagger.Module
import dagger.Provides

interface InstanceDependency
interface ObjectDependency

@Module
class InstanceModule {
  @Provides
  fun value(dependency: InstanceDependency? = null): String = dependency.toString()
}

@Module
object ObjectModule {
  @Provides
  fun number(dependency: ObjectDependency? = null): Int = dependency.hashCode()
}

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph(bindingContainers = [InstanceModule::class, ObjectModule::class])
interface <!MISSING_BINDING!>AppGraph<!> {
  val value: String
  val number: Int
}
