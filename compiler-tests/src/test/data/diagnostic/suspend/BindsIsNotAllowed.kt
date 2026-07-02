// RENDER_DIAGNOSTICS_FULL_TEXT

interface SuspendBindsInterface {
  @Binds suspend fun String.<!BINDS_ERROR!>bind<!>(): CharSequence
}

abstract class SuspendBindsClass {
  @Binds abstract suspend fun String.<!BINDS_ERROR!>bind<!>(): CharSequence
}
