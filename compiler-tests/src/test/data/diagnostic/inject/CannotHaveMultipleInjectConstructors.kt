// RENDER_DIAGNOSTICS_FULL_TEXT

package test

class ExampleClass <!CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS!>@Inject<!> constructor() {
  <!CANNOT_HAVE_MULTIPLE_INJECTED_CONSTRUCTORS!>@Inject<!> constructor(value: String) : this()
}
