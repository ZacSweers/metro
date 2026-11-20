// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

package test

@Singleton
@DependencyGraph(AppScope::class)
interface <!INCOMPATIBLE_SCOPE!>ExampleGraph<!> {

  val intValue: Int

  @SingleIn(UserScope::class)
  @Provides
  fun invalidScope(): Int = 0
}

abstract class UserScope private constructor()
@Scope annotation class Singleton
