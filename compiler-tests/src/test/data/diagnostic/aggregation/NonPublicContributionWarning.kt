// RENDER_DIAGNOSTICS_FULL_TEXT
// NON_PUBLIC_CONTRIBUTION_SEVERITY: WARN

interface SomeInterface

// Warnings
@Inject
@ContributesBinding(AppScope::class)
<!NON_PUBLIC_CONTRIBUTION_WARNING!>internal<!> class InternalContribution : SomeInterface

@ContributesTo(AppScope::class)
<!NON_PUBLIC_CONTRIBUTION_WARNING!>private<!> interface PrivateContribution : SomeInterface

internal object InternalObject {
  @Inject
  @ContributesBinding(AppScope::class)
  class <!NON_PUBLIC_CONTRIBUTION_WARNING!>PublicContributionInObject<!> : SomeInterface
}

abstract class AbstractClass {
  @ContributesTo(AppScope::class)
  <!NON_PUBLIC_CONTRIBUTION_WARNING!>protected<!> interface ProtectedContribution : SomeInterface
}


// Ok
@Inject
@ContributesBinding(AppScope::class)
class PublicContribution : SomeInterface

@Inject
@ContributesBinding(AppScope::class)
public class PublicContribution2 : SomeInterface

// Internal scope - internal contributions to internal scopes should not warn
internal abstract class InternalScope private constructor()

@Inject
@ContributesBinding(InternalScope::class)
internal class InternalContributionToInternalScope : SomeInterface

@Inject
@ContributesBinding(InternalScope::class)
class PublicContributionToInternalScope : SomeInterface

// Protected scope - protected scopes are treated as public (only used for naming)
abstract class OuterWithProtectedScope {
  protected abstract class ProtectedScope private constructor()

  @Inject
  @ContributesBinding(ProtectedScope::class)
  class PublicContributionToProtectedScope : SomeInterface
}
