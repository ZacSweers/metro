// CONTRIBUTES_AS_INJECT

interface Base

@ContributesBinding(AppScope::class)
class Impl(val int: Int) : Base {
  @Inject constructor() : this(3)
}