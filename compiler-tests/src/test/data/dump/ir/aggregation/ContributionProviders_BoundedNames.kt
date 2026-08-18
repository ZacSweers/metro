// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MAX_GENERATED_CLASS_NAME_LENGTH: 150
// MIN_COMPILER_VERSION: 2.3.20

// Keep the exact names of nested factories and companions visible. Multiple scoped bindings also
// exercise the synthetic provider shared by the public contribution methods.
abstract class AccountUserScope private constructor()

interface ObserveMapUserLocationStyleUseCase
interface AlternateUseCase
class DefaultConfig
fun defaultConfig(): DefaultConfig = DefaultConfig()

@ContributesBinding(AccountUserScope::class, binding = binding<ObserveMapUserLocationStyleUseCase>())
@ContributesBinding(AccountUserScope::class, binding = binding<AlternateUseCase>())
@SingleIn(AccountUserScope::class)
@Inject
internal class ObserveMapUserLocationStyleUseCaseImpl(
  val config: DefaultConfig = defaultConfig(),
) : ObserveMapUserLocationStyleUseCase, AlternateUseCase
