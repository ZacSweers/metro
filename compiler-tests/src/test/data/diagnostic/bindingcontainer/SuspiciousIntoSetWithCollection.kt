// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
object Bindings {
  @Provides
  @IntoSet
  fun provideIntSet(): <!SUSPICIOUS_SET_INTO_SET!>Set<Int><!> = setOf(1)
  @Provides
  @IntoSet
  fun provideLongList(): <!SUSPICIOUS_SET_INTO_SET!>List<Long><!> = listOf(1)
}
