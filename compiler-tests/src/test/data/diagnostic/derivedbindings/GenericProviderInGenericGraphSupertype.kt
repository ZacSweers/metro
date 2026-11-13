// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// Nothing substitutes a generic graph supertype's type parameters. Its generic providers don't
// match any key, just like its ordinary providers whose keys mention those type parameters.
class Holder<T>(val name: String)

interface BaseBindings<A> {
  @Provides fun <B> list(b: Holder<B>): List<Holder<B>> = listOf(b)

  @Provides fun plain(a: Holder<A>): Set<Holder<A>> = setOf(a)
}

@DependencyGraph
interface AppGraph : BaseBindings<String> {
  val <!MISSING_BINDING!>list<!>: List<Holder<Int>>
  val <!MISSING_BINDING!>plain<!>: Set<Holder<String>>

  @Provides fun string(): Holder<String> = Holder("string")

  @Provides fun int(): Holder<Int> = Holder("int")
}
