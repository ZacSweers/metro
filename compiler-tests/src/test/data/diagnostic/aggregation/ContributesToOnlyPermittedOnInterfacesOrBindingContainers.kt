// RENDER_DIAGNOSTICS_FULL_TEXT

// Valid: @ContributesTo on an interface
@ContributesTo(AppScope::class)
interface ContributedInterface

// Valid: @ContributesTo on a @BindingContainer-annotated class
@ContributesTo(AppScope::class)
@BindingContainer
class ValidContributedContainerClass

// Valid: @ContributesTo on a @BindingContainer-annotated abstract class
@ContributesTo(AppScope::class)
@BindingContainer
abstract class ValidContributedContainerAbstractClass

// Valid: @ContributesTo on a @BindingContainer-annotated object
@ContributesTo(AppScope::class)
@BindingContainer
object ValidContributedContainerObject

<!AGGREGATION_ERROR!>@ContributesTo(AppScope::class)<!>
class ContributedClass

<!AGGREGATION_ERROR!>@ContributesTo(AppScope::class)<!>
abstract class ContributedAbstractClass

<!AGGREGATION_ERROR!>@ContributesTo(AppScope::class)<!>
object ContributedObject
