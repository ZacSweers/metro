// RENDER_DIAGNOSTICS_FULL_TEXT
// PUBLIC_SCOPED_PROVIDER_SEVERITY: NONE

@Suppress("NOTHING_TO_INLINE", "INLINABLE_PROVIDES_WARNING")
@BindingContainer
object Bindings {
  // scoped
  @Provides @SingleIn(AppScope::class) <!INLINE_PROVIDES_WARNING!>inline<!> fun provideInt(): Int = 3

  // private
  @Provides
  private <!INLINE_PROVIDES_WARNING!>inline<!> fun provideBoolean(): Boolean = false

  // internal missing @PublishedApi
  @Provides internal <!INLINE_PROVIDES_WARNING!>inline<!> fun provideFloat(): Float = 3F

  // internal with @PublishedApi
  @PublishedApi @Provides internal inline fun provideLong(): Long = 3L

  // public
  @Provides inline fun provideDouble(): Double = 3.0
}

@Suppress("NOTHING_TO_INLINE", "INLINABLE_PROVIDES_WARNING")
@BindingContainer
internal object InternalBindings {
  // @PublishedApi in an internal container without @PublishedApi
  @PublishedApi @Provides internal <!INLINE_PROVIDES_WARNING!>inline<!> fun provideShort(): Short = 3
}

@Suppress("NOTHING_TO_INLINE", "INLINABLE_PROVIDES_WARNING")
@PublishedApi
@BindingContainer
internal object PublishedBindings {
  // @PublishedApi in a @PublishedApi internal container
  @PublishedApi @Provides internal inline fun provideByte(): Byte = 3

  // public in a @PublishedApi internal container
  @Provides inline fun provideChar(): Char = 'a'
}
