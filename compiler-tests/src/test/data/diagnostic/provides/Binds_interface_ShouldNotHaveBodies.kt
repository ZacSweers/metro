// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_PROVIDER_SEVERITY: WARN

interface ExampleGraph {
  @Binds val Int.bind: Number get() = this
  @Binds fun String.<!PROVIDES_OR_BINDS_SHOULD_BE_PRIVATE_WARNING!>bind<!>(): CharSequence = this
}
