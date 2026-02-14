// RENDER_DIAGNOSTICS_FULL_TEXT

class Example {
  @Inject fun setterInject(value: String, int: Int = <!MEMBERS_INJECT_ERROR!>3<!>, long: Long? = <!MEMBERS_INJECT_ERROR!>null<!>) {

  }
}
