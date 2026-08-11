import kotlin.reflect.KClass

class QualifierTarget

typealias QualifierTargetAlias = QualifierTarget

@Qualifier
annotation class QualifiedBy(val value: KClass<*>)

typealias QualifiedByAlias = QualifiedBy

enum class Flavor {
  VANILLA
}

typealias FlavorAlias = Flavor

@Qualifier
annotation class Flavored(val value: Flavor)

@Qualifier
annotation class ArrayQualifiedBy(
  val targets: Array<KClass<*>>,
  val flavors: Array<Flavor>,
)

@DependencyGraph
interface AppGraph {
  @QualifiedBy(QualifierTargetAlias::class) val value: String

  @Flavored(Flavor.VANILLA) val flavoredValue: Int

  @ArrayQualifiedBy(
    targets = [QualifierTargetAlias::class],
    flavors = [Flavor.VANILLA],
  )
  val arrayQualifiedValue: Long

  @Provides
  @QualifiedByAlias(QualifierTarget::class)
  fun provideValue(): String = "qualified"

  @Provides
  @Flavored(FlavorAlias.VANILLA)
  fun provideFlavoredValue(): Int = 42

  @Provides
  @ArrayQualifiedBy(
    targets = [QualifierTarget::class],
    flavors = [FlavorAlias.VANILLA],
  )
  fun provideArrayQualifiedValue(): Long = 43L
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("qualified", graph.value)
  assertEquals(42, graph.flavoredValue)
  assertEquals(43L, graph.arrayQualifiedValue)
  return "OK"
}
