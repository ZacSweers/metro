// RENDER_DIAGNOSTICS_FULL_TEXT

@Named("qualified")
@AssistedInject
class <!ASSISTED_INJECTION_ERROR!>Taco<!>(@Assisted val seasoning: String) {
  @AssistedFactory
  interface Factory {
    fun create(seasoning: String): Taco
  }
}

@Suppress("SUGGEST_CLASS_INJECTION")
@Named("qualified")
class <!ASSISTED_INJECTION_ERROR!>Taco2<!> @AssistedInject constructor(@Assisted val seasoning: String) {
  @AssistedFactory
  interface Factory {
    fun create(seasoning: String): Taco
  }
}
