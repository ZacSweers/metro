// ASSISTED_INJECT_MIGRATION_SEVERITY: ERROR
// RENDER_DIAGNOSTICS_FULL_TEXT

@Inject
class <!ASSISTED_INJECTION_ERROR!>Example<!>(@Assisted input: String)

@Inject
class <!ASSISTED_INJECTION_ERROR!>Example2<!> {
  @AssistedFactory
  interface Factory {
    fun create(): Example2
  }
}
