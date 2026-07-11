// ENABLE_SUSPEND_PROVIDERS

// RENDER_DIAGNOSTICS_FULL_TEXT

// Suspend wrappers must wrap the binding type directly (or a multibinding collection). Nesting
// them inside each other or inside Provider/Lazy is statically invalid: no graph can make the
// combination meaningful and codegen has no materialization for it.

@Inject
class BadConsumer(
  val a: <!OPT_IN_USAGE, UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>suspend () -> <!OPT_IN_USAGE!>SuspendLazy<String><!><!>,
  val b: <!OPT_IN_USAGE, UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>SuspendLazy<<!OPT_IN_USAGE!>SuspendLazy<String><!>><!>,
  val c: <!OPT_IN_USAGE, UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>SuspendLazy<suspend () -> String><!>,
  val d: <!DESUGARED_PROVIDER_WARNING, OPT_IN_USAGE, UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>Provider<<!OPT_IN_USAGE!>SuspendLazy<String><!>><!>,
  val e: <!UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>Lazy<suspend () -> String><!>,
)

@Inject
class OkConsumer(
  val ok1: suspend () -> String,
  val ok2: <!OPT_IN_USAGE!>SuspendLazy<String><!>,
  val ok3: Map<String, suspend () -> Int>,
)

@DependencyGraph
interface ExampleGraph {
  val consumer: BadConsumer
  val okConsumer: OkConsumer

  val badAccessor: <!UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>suspend () -> suspend () -> String<!>

  val badMap: <!OPT_IN_USAGE, UNSUPPORTED_SUSPEND_WRAPPER_NESTING!>Map<String, <!OPT_IN_USAGE!>SuspendLazy<Int><!>><!>

  // Provider around a deferred-value map is fine — the suspend wrapper is a map value, not
  // directly nested.
  val okWrappedMap: <!DESUGARED_PROVIDER_WARNING!>Provider<Map<String, suspend () -> Int>><!>

  @Provides suspend fun provideString(): String = "hello"

  @Provides @IntoMap @StringKey("a") suspend fun provideInt(): Int = 1
}
