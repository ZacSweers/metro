// RENDER_DIAGNOSTICS_FULL_TEXT

@Inject
class <!AMBIGUOUS_INJECT_CONSTRUCTOR!>AccountManager<!> {
  constructor()
}

// ok cases

@Inject
class AccountManager2 constructor() {

}

@Inject
class AccountManager3 constructor(int: Int = 3) {
  constructor() : this(3)
}
