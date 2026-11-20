// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: keys-populated/AppGraph/Impl

// Both modules produce reports with the same paths. The shared check reads the last module's report.
// MODULE: lib
// CHECK_REPORTS: keys-validated/AppGraph/Impl
// FILE: Library.kt

@DependencyGraph
interface AppGraph {
  val text: String

  @Provides fun provideText(): String = "lib"
}

// MODULE: main(lib)
// FILE: Main.kt

@DependencyGraph
interface AppGraph {
  val number: Int

  @Provides fun provideNumber(): Int = 1
}
