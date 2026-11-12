// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

// A chain of 65 derived bindings passes the depth limit of 64.
interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

typealias L1 = List<String>

typealias L2 = List<L1>

typealias L3 = List<L2>

typealias L4 = List<L3>

typealias L5 = List<L4>

typealias L6 = List<L5>

typealias L7 = List<L6>

typealias L8 = List<L7>

typealias L9 = List<L8>

typealias L10 = List<L9>

typealias L11 = List<L10>

typealias L12 = List<L11>

typealias L13 = List<L12>

typealias L14 = List<L13>

typealias L15 = List<L14>

typealias L16 = List<L15>

typealias L17 = List<L16>

typealias L18 = List<L17>

typealias L19 = List<L18>

typealias L20 = List<L19>

typealias L21 = List<L20>

typealias L22 = List<L21>

typealias L23 = List<L22>

typealias L24 = List<L23>

typealias L25 = List<L24>

typealias L26 = List<L25>

typealias L27 = List<L26>

typealias L28 = List<L27>

typealias L29 = List<L28>

typealias L30 = List<L29>

typealias L31 = List<L30>

typealias L32 = List<L31>

typealias L33 = List<L32>

typealias L34 = List<L33>

typealias L35 = List<L34>

typealias L36 = List<L35>

typealias L37 = List<L36>

typealias L38 = List<L37>

typealias L39 = List<L38>

typealias L40 = List<L39>

typealias L41 = List<L40>

typealias L42 = List<L41>

typealias L43 = List<L42>

typealias L44 = List<L43>

typealias L45 = List<L44>

typealias L46 = List<L45>

typealias L47 = List<L46>

typealias L48 = List<L47>

typealias L49 = List<L48>

typealias L50 = List<L49>

typealias L51 = List<L50>

typealias L52 = List<L51>

typealias L53 = List<L52>

typealias L54 = List<L53>

typealias L55 = List<L54>

typealias L56 = List<L55>

typealias L57 = List<L56>

typealias L58 = List<L57>

typealias L59 = List<L58>

typealias L60 = List<L59>

typealias L61 = List<L60>

typealias L62 = List<L61>

typealias L63 = List<L62>

typealias L64 = List<L63>

typealias L65 = List<L64>

@DependencyGraph
interface <!DIVERGENT_DERIVED_BINDING!>AppGraph<!> {
  val encoder: Encoder<L65>

  @Provides fun string(): Encoder<String> = SimpleEncoder()

  @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = SimpleEncoder()
}
