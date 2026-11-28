// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A dynamic @IntoMap contribution joins the graph's own contributions. It doesn't replace a real
// entry with the same map key, so the two keys collide.
@DependencyGraph
interface AppGraph {
  val <!DUPLICATE_MAP_KEY!>ints<!>: Map<String, Int>

  @Provides @IntoMap @StringKey("key") fun provideInt(): Int = 1
}

@BindingContainer
object FakeInts {
  @Provides @IntoMap @StringKey("key") fun provideInt(): Int = 2
}

fun example() {
  createDynamicGraph<AppGraph>(FakeInts)
}
