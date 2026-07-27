// MIN_COMPILER_VERSION: 2.4.20-Beta1
// ENABLE_PRIVATE_PROVIDER_PROPERTIES

@DependencyGraph
abstract class AppGraph {
  @Provides private val providedInt: Int get() = 42

  abstract val int: Int
}
