// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

class SimpleEncoder<T> : Encoder<T>

// Drops its type argument.
typealias Ignored<T> = String

// Keeps its type argument.
typealias EncoderOf<T> = Encoder<T>

@BindingContainer
object Bindings {
  // The rules apply to the expanded type, which is `String` here.
  @Provides fun <<!METRO_TYPE_PARAMETERS_ERROR!>T<!>> ignored(encoder: Encoder<T>): Ignored<T> = ""

  @Provides fun <T> aliased(): EncoderOf<List<T>> = SimpleEncoder()
}
