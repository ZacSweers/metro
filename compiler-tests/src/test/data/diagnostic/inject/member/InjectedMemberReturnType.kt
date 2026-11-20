// RUN_PIPELINE_TILL: BACKEND
// RENDER_DIAGNOSTICS_FULL_TEXT

class ExampleClass {
  @Inject fun implicitUnit(value: Int) {}
  @Inject fun explicitUnit(value: Int): Unit {}
  @Inject fun nothing(value: Int): <!MEMBERS_INJECT_RETURN_TYPE_WARNING!>Nothing<!> = TODO()
}
