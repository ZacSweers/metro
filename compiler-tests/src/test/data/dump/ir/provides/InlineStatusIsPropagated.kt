@BindingContainer
object Bindings {
  @Provides inline fun provideInt(): Int = 3

  @Provides
  @PublishedApi
  internal inline fun provideBoolean(): Boolean = false

  // Should not propagate because of the visibility
  @Provides private inline fun provideLong(): Long = 3L

  // Should not propagate because of the visibility and lack of @PublishedApi
  @Provides internal inline fun provideFloat(): Float = 3F
}