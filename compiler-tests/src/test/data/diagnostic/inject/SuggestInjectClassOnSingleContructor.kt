// RENDER_DIAGNOSTICS_FULL_TEXT
class SingleEmptyConstructor <!SUGGEST_CLASS_INJECTION!>@Inject<!> constructor()

class SingleNonEmptyConstructor <!SUGGEST_CLASS_INJECTION!>@Inject<!> constructor(int: Int)

class InjectPrimaryConstructor @Inject constructor(int: Int) {
  constructor() : this(0)
}

class InjectSecondaryConstructor internal constructor(int: Int) {
  @Inject constructor() : this(0)
}
