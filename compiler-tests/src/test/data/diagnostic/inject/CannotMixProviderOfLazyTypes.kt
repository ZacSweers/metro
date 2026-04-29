// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP
// DESUGARED_PROVIDER_SEVERITY: NONE

@Inject
class Example(
  // Ok
  val one: Provider<Lazy<Int>>,
  // Bad
  val two: <!PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY!>Provider<dagger.Lazy<Int>><!>,
  val twoFunction: <!PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY!>() -> dagger.Lazy<Int><!>,
  val three: <!PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY!>javax.inject.Provider<Lazy<Int>><!>,
  val four: <!PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY!>jakarta.inject.Provider<Lazy<Int>><!>,
  val five: <!PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY!>jakarta.inject.Provider<dagger.Lazy<Int>><!>,
  val six: <!PROVIDERS_OF_LAZY_MUST_BE_METRO_ONLY!>javax.inject.Provider<dagger.Lazy<Int>><!>,
)
