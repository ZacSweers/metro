enum class Animal {
    DOG, CAT;
}

@MapKey
annotation class EnumMapKey(val value: Animal)

@DependencyGraph
interface ExampleGraph {
    @Provides
    @IntoMap
    @EnumMapKey(Animal.DOG)
    fun provideDog(): String = "dog"

    @Provides
    @IntoMap
    @EnumMapKey(Animal.CAT)
    fun provideCat(): String = "cat"

    @Multibinds(allowEmpty = true)
    fun primeMap(): Map<Animal, String>

    val directMap: Map<Animal, String>
}

fun box(): String {
    val graph = createGraph<ExampleGraph>()

    assertEquals(mapOf(Animal.DOG to "dog", Animal.CAT to "cat"), graph.directMap)
    return "OK"
}

