// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_SCOPED_PROVIDER_SEVERITY: NONE

@Suppress("NOTHING_TO_INLINE")
@BindingContainer
object Bindings {
  // scoped
  @Provides @SingleIn(AppScope::class) <!INLINE_PROVIDES_WARNING!>inline<!> fun provideInt(): Int = 3

  // private
  @Provides
  private <!INLINE_PROVIDES_WARNING!>inline<!> fun provideBoolean(): Boolean = false

  // internal missing @PublishedApi
  @Provides internal <!INLINE_PROVIDES_WARNING!>inline<!> fun provideFloat(): Float = 3F
}
