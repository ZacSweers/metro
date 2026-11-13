// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DERIVED_BINDINGS

interface Encoder<T>

@BindingContainer
object Bindings {
  @Provides fun <T> anything(encoder: Encoder<T>): <!METRO_TYPE_PARAMETERS_ERROR!>T<!> = throw UnsupportedOperationException()

  @Provides fun <T> nullableAnything(encoder: Encoder<T>): <!METRO_TYPE_PARAMETERS_ERROR!>T?<!> = null
}

@BindingContainer
interface BindsBindings {
  @Binds fun <T> bindAnything(encoder: Encoder<T>): <!METRO_TYPE_PARAMETERS_ERROR!>T<!>
}
