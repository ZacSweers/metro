// RENDER_DIAGNOSTICS_FULL_TEXT

@DefaultBinding<Base1>
interface Base1

@DefaultBinding<Base2>
interface Base2

// Multiple supertypes with @DefaultBinding and no explicit binding -> error
@Inject
<!AGGREGATION_ERROR!>@ContributesBinding(AppScope::class)<!>
class Impl : Base1, Base2
