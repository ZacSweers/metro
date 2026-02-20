// CHECK_REPORTS: keys-unused-AppGraph_Impl
// CHECK_REPORTS: keys-unused-AppGraph_Impl_ChildGraphImpl
// CHECK_REPORTS: keys-unused-AppGraph_Impl_ChildGraphImpl_GrandchildGraphImpl
// CHECK_REPORTS: keys-unused-AppGraph_Impl_ChildGraphImpl_GrandchildGraphImpl_GreatGrandchildGraphImpl

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val child: ChildGraph

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides string: String, @Provides unused: Long): AppGraph
  }
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val int: Int
  val grandchild: GrandchildGraph

  @Provides fun provideInt(): Int = 42
  @Provides fun provideUnusedDouble(): Double = 1.0
}

@GraphExtension(GrandchildScope::class)
interface GrandchildGraph {
  val short: Short
  val greatGrandchild: GreatGrandchildGraph

  @Provides fun provideShort(): Short = 1
  @Provides fun provideUnusedFloat(): Float = 1.0f
}

@GraphExtension(GreatGrandchildScope::class)
interface GreatGrandchildGraph {
  val byte: Byte

  @Provides fun provideByte(): Byte = 1
  @Provides fun provideUnusedChar(): Char = 'a'
}

abstract class AppScope private constructor()
abstract class ChildScope private constructor()
abstract class GrandchildScope private constructor()
abstract class GreatGrandchildScope private constructor()
