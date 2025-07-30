// KOTLINC_ARGS: -Xcontext-parameters
// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
@Inject
<!UNSUPPORTED_FEATURE!>context(<!FUNCTION_INJECT_ERROR!>value<!>: String)<!>
fun <!FUNCTION_INJECT_ERROR!>String<!>.Example() {

}

/* GENERATED_FIR_TAGS: funWithExtensionReceiver, functionDeclaration, functionDeclarationWithContext */
