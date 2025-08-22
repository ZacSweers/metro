interface ExampleGraph {
  @Binds val Int.bind: Number
  @Binds fun String.bind(): CharSequence
}

/* GENERATED_FIR_TAGS: funWithExtensionReceiver, functionDeclaration, interfaceDeclaration, propertyDeclaration,
propertyWithExtensionReceiver */
