// RENDER_DIAGNOSTICS_FULL_TEXT

@MapKey
annotation class Tag(val value: String)

// Members of a plain interface may carry a map key that another tool consumes.
interface PlainAccessors {
  @Tag("plain")
  val plain: String
}

@ContributesTo(AppScope::class)
interface ContributedAccessors {
  <!MULTIBINDS_ERROR!>@Tag("contributed")<!>
  val contributed: Int
}

@BindingContainer
object Bindings {
  <!MULTIBINDS_ERROR!>@Tag("provides")<!> @Provides fun provideLong(): Long = 1L
}

@DependencyGraph(AppScope::class)
interface AppGraph : PlainAccessors {
  <!MULTIBINDS_ERROR!>@Tag("accessor")<!>
  val accessor: String

  @Provides fun provideString(): String = "hi"

  @Provides fun provideInt(): Int = 1
}
