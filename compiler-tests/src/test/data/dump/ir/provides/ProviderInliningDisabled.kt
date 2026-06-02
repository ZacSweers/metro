// ENABLE_PROVIDER_INLINING: false

@BindingContainer
object Bindings {
  @Provides fun provideInt(): Int = 3
}
