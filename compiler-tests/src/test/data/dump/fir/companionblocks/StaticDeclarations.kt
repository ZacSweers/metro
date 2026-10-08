// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK
@Inject class Value private constructor(val text: String)
@DependencyGraph
interface AppGraph {
  val value: Value
  companion { @Provides fun text(): String = "OK" }
}
