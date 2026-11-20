// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph(Unit::class)
interface ExampleGraph {
  val int: Int
  val nullableInt: Int?

  @Provides
  fun provideInt(): Int = 1

  @Binds
  val Int?.<!PROVIDES_ERROR!>bindAsNullable<!>: Int
}
