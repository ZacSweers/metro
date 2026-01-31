// RENDER_DIAGNOSTICS_FULL_TEXT
// LANGUAGE: +NestedTypeAliases

typealias UserId = String

@DependencyGraph
interface AppGraph {
  typealias NestedUserId = String

  val userId: UserId

  @Binds fun String.<!PROVIDES_ERROR!>bind<!>(): UserId

  @Binds fun String.<!PROVIDES_ERROR!>bindNested<!>(): NestedUserId

  @Provides fun provideString(): String = "Hello"
}
