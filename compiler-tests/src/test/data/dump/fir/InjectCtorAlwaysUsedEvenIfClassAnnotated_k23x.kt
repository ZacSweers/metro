// CONTRIBUTES_AS_INJECT
// MAX_COMPILER_VERSION: 2.3.21

interface Base

@ContributesBinding(AppScope::class)
class Impl(val int: Int) : Base {
  @Inject constructor() : this(3)
}