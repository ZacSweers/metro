// In this scenario, generated provider factories' newInstance function gets the field directly
// However, access cannot yet be directly invoked until we support inlining field access calls to
// public fields
@DependencyGraph
abstract class AppGraph {
  @Provides @JvmField val stringField: String = "Hello"

  abstract val string: String
}
