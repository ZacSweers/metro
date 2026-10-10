// AppGraph -> Child -> (GrandchildC, GrandchildD), AppGraph -> ChildE.
// Bindings all three leaves read are built once in AppGraph. So are bindings GrandchildC and ChildE
// read, since AppGraph is their nearest shared parent. Bindings only the grandchildren read are
// built once in Child. Child changes Set<Long>, so the grandchildren share Child's set and ChildE
// builds AppGraph's set itself.
@Inject class Dependency

@Inject class SharedByAll(val dependency: Dependency)

@Inject class SharedByGrandchildren(val dependency: Dependency)

@SingleIn(AppScope::class) @Inject class AppSingleton

@Inject class SharedByCAndE(val singleton: AppSingleton)

@GraphExtension
interface GrandchildC {
  val sharedByAll: SharedByAll
  val sharedByGrandchildren: SharedByGrandchildren
  val sharedByCAndE: SharedByCAndE
  val strings: Set<String>
  val longs: Set<Long>

  @GraphExtension.Factory
  interface Factory {
    fun createGrandchildC(): GrandchildC
  }
}

@GraphExtension
interface GrandchildD {
  val sharedByAll: SharedByAll
  val sharedByGrandchildren: SharedByGrandchildren
  val strings: Set<String>
  val longs: Set<Long>

  @GraphExtension.Factory
  interface Factory {
    fun createGrandchildD(): GrandchildD
  }
}

@GraphExtension
interface Child : GrandchildC.Factory, GrandchildD.Factory {
  @Provides @IntoSet fun provideChildLong(): Long = 2L

  @GraphExtension.Factory
  interface Factory {
    fun createChild(): Child
  }
}

@GraphExtension
interface ChildE {
  val sharedByAll: SharedByAll
  val sharedByCAndE: SharedByCAndE
  val strings: Set<String>
  val longs: Set<Long>

  @GraphExtension.Factory
  interface Factory {
    fun createChildE(): ChildE
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph : Child.Factory, ChildE.Factory {
  @Provides fun provideInt(): Int = 3

  @Provides @IntoSet fun provideOne(int: Int): String = "one$int"

  @Provides @IntoSet fun provideTwo(int: Int): String = "two$int"

  @Provides @IntoSet fun provideAppLong(): Long = 1L
}
