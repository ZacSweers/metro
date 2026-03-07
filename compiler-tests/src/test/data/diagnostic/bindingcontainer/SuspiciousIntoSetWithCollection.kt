// RENDER_DIAGNOSTICS_FULL_TEXT

@BindingContainer
object Bindings {
  @Provides
  @IntoSet
  fun provideIntSet(): <!SUSPICIOUS_SET_INTO_SET!>Set<Int><!> = setOf(1)
  @Provides
  @IntoSet
  val provideLongList: <!SUSPICIOUS_SET_INTO_SET!>List<Long><!> get() = listOf(1)
}
