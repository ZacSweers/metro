// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass {
  @Inject suspend fun <!MEMBERS_INJECT_ERROR!>setValue<!>(value: Int) {

  }
}
