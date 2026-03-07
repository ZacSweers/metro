// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MODULE: lib
package test

import kotlin.reflect.KClass

// Custom annotation that triggers the test extension to generate a @ContributesTo interface
// with a @Provides function
@Target(AnnotationTarget.CLASS)
annotation class GenerateProvidesContribution(val scope: KClass<*>)

// The extension generates:
//   @ContributesTo(AppScope::class)
//   interface ProvidesContribution {
//     @Provides fun provideCharSequenceValue(): CharSequenceValue = CharSequenceValue()
//   }
@GenerateProvidesContribution(AppScope::class)
class CharSequenceValue : CharSequence {
  override val length = 5
  override fun get(index: Int) = "hello"[index]
  override fun subSequence(startIndex: Int, endIndex: Int) = "hello".subSequence(startIndex, endIndex)
}

// MODULE: main(lib)
package test

@DependencyGraph(AppScope::class)
interface AppGraph {
  val charSequenceValue: CharSequenceValue
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val value = graph.charSequenceValue
  assertEquals(5, value.length)
  return "OK"
}
