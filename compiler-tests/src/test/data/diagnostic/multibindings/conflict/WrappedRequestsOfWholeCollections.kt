// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Provider and Lazy requests use the same key as a plain request.
@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>SetGraph<!> {
  val strings: Lazy<Set<String>>

  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides @IntoSet fun provideChild(): String = "child"
}

// A direct map with function values conflicts when it's requested in that form.
@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ProviderMapGraph<!> {
  val ints: Lazy<Map<String, () -> Int>>

  @Provides fun provideInts(): Map<String, () -> Int> = mapOf("whole" to { 1 })

  @Provides @IntoMap @StringKey("child") fun provideChild(): Int = 2
}
