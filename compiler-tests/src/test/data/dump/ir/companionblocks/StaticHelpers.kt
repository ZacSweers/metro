// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

@Inject
class InjectedValue<T> private constructor(val value: T) {
  @Inject lateinit var values: List<T>
}

@Inject
class SingletonValue

@DependencyGraph
interface AppGraph {
  val value: InjectedValue<String>
  val singleton: SingletonValue

  @Provides fun value(): String = "static"
  @Provides fun values(): List<String> = listOf("static")
}
