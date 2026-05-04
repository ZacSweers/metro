// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

interface Base

@ContributesBinding(AppScope::class)
@Inject
internal class Impl(
  val string: String,
  val longProvider: () -> Long,
  val longFun: () -> Long,
  val lazyBool: Lazy<Boolean>,
  val providerOfLazyDouble: () -> Lazy<Double>,
) : Base
