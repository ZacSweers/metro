// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@Inject
class ExampleClass <!CANNOT_HAVE_INJECT_IN_MULTIPLE_TARGETS, SUGGEST_CLASS_INJECTION!>@Inject<!> constructor(private val value: String)
