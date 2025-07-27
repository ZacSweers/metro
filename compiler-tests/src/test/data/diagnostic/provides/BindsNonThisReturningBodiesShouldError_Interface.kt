// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  @Binds val String.<!BINDS_ERROR!>provideCharSequence<!>: CharSequence get() = "something else"
  @Binds fun Int.<!BINDS_ERROR!>provideNumber<!>(): Number = 3
}

/* GENERATED_FIR_TAGS: funWithExtensionReceiver, functionDeclaration, getter, integerLiteral, interfaceDeclaration,
propertyDeclaration, propertyWithExtensionReceiver, stringLiteral */
