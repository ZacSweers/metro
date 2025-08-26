// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: WARN
// DISABLE_TRANSFORM_PROVIDERS_TO_PRIVATE

interface ExampleGraph {
  @Binds val String.provideCharSequence: CharSequence get() = this
  @Binds fun Int.<!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>provideNumber<!>(): Number = this
}
