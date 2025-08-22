// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  @Provides val Long.<!PROVIDES_ERROR!>provideInt<!>: Int get() = this.toInt()
  @Provides private fun CharSequence.<!PROVIDES_ERROR!>provideString<!>(): String = this.toString()
}

/* GENERATED_FIR_TAGS: funWithExtensionReceiver, functionDeclaration, getter, interfaceDeclaration, propertyDeclaration,
propertyWithExtensionReceiver, thisExpression */
