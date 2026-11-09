// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true
@Inject class Value private constructor(val text: String)
@DependencyGraph
interface AppGraph {
  val value: Value
  companion { @Provides fun text(): String = "OK" }
}
