// GENERATE_JVM_CONTRIBUTION_HINTS_IN_FIR

@ContributesTo(AppScope::class)
interface ContributedInterface1

@ContributesTo(Unit::class)
interface ContributedInterface2

// Repeated
@ContributesTo(AppScope::class)
@ContributesTo(Unit::class)
interface ContributedInterface3