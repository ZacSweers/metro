// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
class ExampleBindings {
  @Provides fun <!BINDING_CONTAINER_ERROR!>provideNumber<!>(): Number = 3
  @Provides fun <!BINDING_CONTAINER_ERROR!>provideNumber<!>(string: String): Int = string.length
}
