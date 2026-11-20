// FUNCTION_INJECT_ANNOTATIONS_OVERRIDE: test/InjectFunction
// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@Target(AnnotationTarget.FUNCTION)
annotation class InjectFunction

@InjectFunction
fun <!FUNCTION_INJECT_ERROR!>String<!>.app(): String = this
