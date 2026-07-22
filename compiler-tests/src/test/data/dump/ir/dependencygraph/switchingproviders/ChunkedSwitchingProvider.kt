// ENABLE_SWITCHING_PROVIDERS: true
// STATEMENTS_PER_INIT_FUN: 2

/**
 * Tests that SwitchingProvider routes directly to chunked when branches when the number of
 * bindings exceeds STATEMENTS_PER_INIT_FUN.
 *
 * With STATEMENTS_PER_INIT_FUN=2, invoke() selects a helper using id / 2. The helpers handle IDs
 * 0-1, 2-3, and 4 respectively, and each helper throws for an unexpected ID.
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
