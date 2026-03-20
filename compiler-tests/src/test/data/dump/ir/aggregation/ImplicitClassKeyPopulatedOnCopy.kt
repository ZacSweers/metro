// Ensure that when a contribution uses an implicit class key (@ClassKey with no value),
// the generated binds function gets the annotation populated with the annotated class.

interface Base

@ClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class ImplicitKeyImpl : Base

@ClassKey(Base::class)
@ContributesIntoMap(AppScope::class)
@Inject
class ExplicitKeyImpl : Base
