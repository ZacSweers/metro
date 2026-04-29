@DefaultBinding<Base<*>>
interface Base<T>

interface Other

@ContributesBinding(AppScope::class)
@Inject
class Impl : Base<Impl>, Other
