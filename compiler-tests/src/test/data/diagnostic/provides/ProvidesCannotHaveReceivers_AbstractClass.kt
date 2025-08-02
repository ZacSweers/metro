// RENDER_DIAGNOSTICS_FULL_TEXT

abstract class ExampleGraph {
  @Provides val Long.<!PROVIDES_ERROR!>provideInt<!>: Int get() = this.toInt()
  @Provides private fun CharSequence.<!PROVIDES_ERROR!>provideString<!>(): String = "Hello"
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, getter, propertyDeclaration,
propertyWithExtensionReceiver, stringLiteral, thisExpression */
