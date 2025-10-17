// RENDER_DIAGNOSTICS_FULL_TEXT

interface BindsInterface {
  @Binds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> val String.bind: CharSequence
  @Binds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> fun String.bindFunction(): CharSequence
}

abstract class BindsClass {
  @Binds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> abstract val String.bind: CharSequence
  @Binds <!BINDS_ERROR!>@SingleIn(AppScope::class)<!> abstract fun String.bindFunction(): CharSequence
}
