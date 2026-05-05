// TRACE_DESTINATION: metro/traces
// CHECK_TRACES

@DependencyGraph
interface AppGraph {
  val message: String

  @Provides fun provideMessage(): String = "hi"
}
