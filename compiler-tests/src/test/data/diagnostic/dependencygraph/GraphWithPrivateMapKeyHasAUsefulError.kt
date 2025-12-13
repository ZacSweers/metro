// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
@MapKey
private annotation class MyKey(val int: Int)

@Inject
@MyKey(3)
@ContributesIntoMap(AppScope::class)
class Something

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Multibinds
  val ints: Map<Int, Any>
}