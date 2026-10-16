// RENDER_DIAGNOSTICS_FULL_TEXT
// DIAGNOSTIC_LEVEL: MAP_KEY_WITHOUT_INTO_MAP:warning

@MapKey
annotation class Tag(val value: String)

interface Accessors {
  <!MAP_KEY_WITHOUT_INTO_MAP!>@Tag("lowered")<!>
  val lowered: String
}
