// RENDER_DIAGNOSTICS_FULL_TEXT
// LANGUAGE: +ContextParameters

abstract class Bindings {
  // Receivers are dependencies, so these are valid.
  @Provides val Long.provideInt: Int get() = toInt()
  @Provides private fun CharSequence.provideString(): String = toString()

  // Returning the receiver should be a @Binds.
  @Provides fun String.<!PROVIDES_COULD_BE_BINDS!>provideCharSequence<!>(): CharSequence = this

  @Provides fun <!PROVIDES_ERROR!>@receiver:Assisted<!> Int.provideAssistedReceiver(): Long = toLong()

  @Provides fun <!OPTIONAL_BINDING_ERROR!>@receiver:OptionalBinding Short<!>.provideOptionalReceiver(): Double = toDouble()

  @Provides
  context(<!PROVIDES_ERROR!>@Assisted<!> value: Byte)
  fun provideAssistedContext(): Float = value.toFloat()

  @Provides
  context(@OptionalBinding value: <!OPTIONAL_BINDING_ERROR!>Char<!>)
  fun provideOptionalContext(): Boolean = value.isDefined()
}
