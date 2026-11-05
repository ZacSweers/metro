// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// ENABLE_DAGGER_KSP
// DISABLE_METRO
// FILE: lib.kt
package test

import javax.inject.Inject
import javax.inject.Named

interface BaseProps

class NullableEncoder @Inject constructor(val base: BaseProps? = null)

@Suppress("ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD")
class NonNullableEncoder @Inject constructor(
  @Named("required") val base: BaseProps = object : BaseProps {},
)

// MODULE: main(lib)
// ENABLE_DAGGER_INTEROP
package test

@DependencyGraph
interface <!MISSING_BINDING!>AppGraph<!> {
  val nullableEncoder: NullableEncoder
  val nonNullableEncoder: NonNullableEncoder
}
