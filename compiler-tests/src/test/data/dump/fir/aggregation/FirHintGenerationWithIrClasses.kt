// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CLASSES_IN_IR: true
// MIN_COMPILER_VERSION: 2.4.20-dev-6138

// FILE: FirHintGenerationWithIrClasses.kt

@ContributesTo(AppScope::class)
interface ContributedInterface

interface FirstBinding

@ContributesBinding(AppScope::class)
@Inject
class FirstBindingImpl : FirstBinding
