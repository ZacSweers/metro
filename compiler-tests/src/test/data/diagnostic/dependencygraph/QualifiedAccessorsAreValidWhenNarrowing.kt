// RUN_PIPELINE_TILL: BACKEND
// DIAGNOSTICS: -warnings

package test

interface Parent1 {
  val prop: String
  fun function(): String
}

@DependencyGraph interface AppGraph : Parent1 {
  @Named("qualified") override val prop: String
  @Named("qualified") override fun function(): String

  @Named("qualified") @Provides fun provideString(): String = "hello"
}
