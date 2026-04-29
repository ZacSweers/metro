// RENDER_DIAGNOSTICS_FULL_TEXT

interface SomeInterface

// Errors
@ContributesTo(AppScope::class)
<!PRIVATE_CONTRIBUTION_ERROR!>private<!> interface InternalContribution : SomeInterface

@ContributesTo(AppScope::class)
<!PRIVATE_CONTRIBUTION_ERROR!>private<!> interface PrivateContribution : SomeInterface

private object InternalObject {
  @ContributesTo(AppScope::class)
  interface <!PRIVATE_CONTRIBUTION_ERROR!>PublicContributionInObject<!> : SomeInterface
}

abstract class AbstractClass {
  @ContributesTo(AppScope::class)
  <!PRIVATE_CONTRIBUTION_ERROR!>private<!> interface ProtectedContribution : SomeInterface
}


// Ok
@Inject
@ContributesBinding(AppScope::class)
class PublicContribution : SomeInterface

@Inject
@ContributesBinding(AppScope::class)
public class PublicContribution2 : SomeInterface
