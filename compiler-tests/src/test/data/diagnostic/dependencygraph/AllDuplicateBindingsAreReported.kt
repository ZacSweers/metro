// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!METRO_ERROR, METRO_ERROR, METRO_ERROR, METRO_ERROR!>AppGraph<!> {
  @Provides fun provideString1(): String = "1"
  @Provides fun provideString2(): String = "2"
  @Provides fun provideString3(): String = "3"
  @Provides fun provideString4(): String = "4"
  @Provides fun provideString5(): String = "5"
}
