// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface Providers {
  @Provides <!PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE!>private<!> val providedString: String get() = "Hello"

  @get:Provides <!PROVIDES_PROPERTIES_CANNOT_BE_PRIVATE!>private<!> val providedInt: Int get() = 42
}
