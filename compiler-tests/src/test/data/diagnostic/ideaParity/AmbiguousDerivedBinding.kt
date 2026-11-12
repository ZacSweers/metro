// ENABLE_DERIVED_BINDINGS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// CHECK_REPORTS: keys-populated/parity/failures/derived/AppGraph/Impl

package parity.failures.derived

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Encoders {
  @Provides fun <A> left(): Encoder<Pair<A, Int>> = SimpleEncoder()

  @Provides fun <B> right(): Encoder<Pair<String, B>> = SimpleEncoder()
}

@DependencyGraph(bindingContainers = [Encoders::class])
interface <!AMBIGUOUS_DERIVED_BINDING!>AppGraph<!> {
  val pair: Encoder<Pair<String, Int>>
}

// METRO_JVM_ONLY
