// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A type variable never binds a star, so these generic providers don't match the requested keys.
interface IThing<A, B>

class Thing<A, B> : IThing<A, B>

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

@BindingContainer
object Bindings {
  @Provides fun <T, R> thing(): Thing<T, R> = Thing()

  @Provides fun <T> list(): Encoder<List<T>> = SimpleEncoder()
}

@BindingContainer
interface ThingBindings {
  // This matches `IThing<String, *>` with `T = String`, but nothing provides `Thing<String, *>`.
  @Binds fun <T> Thing<T, *>.asIThing(): IThing<T, *>
}

@DependencyGraph(bindingContainers = [Bindings::class, ThingBindings::class])
interface AppGraph {
  val <!MISSING_BINDING!>thing<!>: Thing<String, *>
  val iThing: IThing<String, *>
  val <!MISSING_BINDING!>listOfStars<!>: Encoder<List<*>>
}
