// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass(
  @Inject val <!MEMBERS_INJECT_STATUS_ERROR!>int<!>: Int,
  @set:Inject var <!MEMBERS_INJECT_STATUS_ERROR!>int2<!>: Int,
)
