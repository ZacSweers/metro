// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: WARN

abstract class ExampleGraph {
  @Binds val String.provideCharSequence: CharSequence get() = this
  @Binds fun Int.<!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideNumber<!>(): Number = this
}
