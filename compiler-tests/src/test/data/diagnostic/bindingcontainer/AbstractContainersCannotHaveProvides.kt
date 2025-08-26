// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
interface ConfusedContainer {
  // This is not legal
  @Provides fun <!BINDING_CONTAINER_ERROR!>provideInt<!>(): Int = 3

  companion object {
    // This is legal
    @Provides fun provideBoolean(): Boolean = false
  }
}
