// RENDER_DIAGNOSTICS_FULL_TEXT

package test

abstract class ExampleClass {
  @set:Inject <!MEMBERS_INJECT_STATUS_ERROR!>abstract<!> var intSetter: Int
  @Inject <!MEMBERS_INJECT_STATUS_ERROR!>abstract<!> fun intFunction(int: Int)
}
