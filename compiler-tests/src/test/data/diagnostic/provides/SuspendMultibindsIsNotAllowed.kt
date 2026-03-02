// RENDER_DIAGNOSTICS_FULL_TEXT

interface SuspendMultibindsInterface {
  @Multibinds suspend fun <!MULTIBINDS_ERROR!>bind<!>(): Set<String>
}

abstract class SuspendMultibindsClass {
  @Multibinds abstract suspend fun <!MULTIBINDS_ERROR!>bind<!>(): Set<String>
}
