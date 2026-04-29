// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// Verify the synthetic scoped provider pattern for multiple bindings from a scoped class.

interface Foo

interface Bar

@ContributesBinding(AppScope::class, binding = binding<Foo>())
@ContributesBinding(AppScope::class, binding = binding<Bar>())
@SingleIn(AppScope::class)
@Inject
internal class Impl(
  val string: String,
  val longProvider: () -> Long,
  val longFun: () -> Long,
  val lazyBool: Lazy<Boolean>,
  val providerOfLazyDouble: () -> Lazy<Double>,
) : Foo, Bar
