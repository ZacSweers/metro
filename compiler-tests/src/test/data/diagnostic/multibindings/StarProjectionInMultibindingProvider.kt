// RENDER_DIAGNOSTICS_FULL_TEXT
// DIAGNOSTICS: -DESUGARED_PROVIDER_WARNING -DESUGARED_PROVIDER_ERROR

interface ExampleGraph {
  @Multibinds(allowEmpty = true)
  val strings: <!MULTIBINDS_ERROR!>Map<String, Provider<*>><!>
}
