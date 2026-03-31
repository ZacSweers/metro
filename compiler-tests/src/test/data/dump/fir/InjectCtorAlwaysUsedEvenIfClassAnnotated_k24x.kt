// CONTRIBUTES_AS_INJECT
// COMPILER_VERSION: 2.4

interface Base

@ContributesBinding(AppScope::class)
class Impl(val int: Int) : Base {
  @Inject constructor() : this(3)
}