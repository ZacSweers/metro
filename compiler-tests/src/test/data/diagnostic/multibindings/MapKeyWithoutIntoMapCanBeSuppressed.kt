// RENDER_DIAGNOSTICS_FULL_TEXT

@MapKey
annotation class Tag(val value: String)

// Another tool reads these keys and generates the `@IntoMap` bindings.
interface Accessors {
  <!MAP_KEY_WITHOUT_INTO_MAP!>@Tag("unsuppressed")<!>
  val unsuppressed: String

  @Suppress("MAP_KEY_WITHOUT_INTO_MAP")
  @Tag("suppressed")
  val suppressed: String
}
