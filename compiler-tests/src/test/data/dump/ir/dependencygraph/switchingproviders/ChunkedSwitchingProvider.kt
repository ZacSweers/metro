// ENABLE_SWITCHING_PROVIDERS: true
// STATEMENTS_PER_INIT_FUN: 2

/**
 * Tests that SwitchingProvider correctly chunks its when branches across multiple private helper
 * functions when the number of bindings exceeds STATEMENTS_PER_INIT_FUN.
 *
 * With STATEMENTS_PER_INIT_FUN=2, the 5 provider bindings should be split as:
 * - invoke(): handles IDs 0-1, else -> invoke_1()
 * - invoke_1(): handles IDs 2-3, else -> invoke_2()
 * - invoke_2(): handles ID 4, else -> error
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
  val a: ClassA
  val b: ClassB
  val c: ClassC
  val d: ClassD
  val e: ClassE
}

@Inject @SingleIn(AppScope::class) class ClassA

@Inject @SingleIn(AppScope::class) class ClassB

@Inject @SingleIn(AppScope::class) class ClassC

@Inject @SingleIn(AppScope::class) class ClassD

@Inject @SingleIn(AppScope::class) class ClassE
