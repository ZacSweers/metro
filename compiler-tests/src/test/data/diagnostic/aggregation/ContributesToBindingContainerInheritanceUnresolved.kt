// RENDER_DIAGNOSTICS_FULL_TEXT

// Unresolved ancestry prevents a recommendation whose conversion could discard inherited members.
@ContributesTo(AppScope::class)
interface UnresolvedParentChild : <!UNRESOLVED_REFERENCE!>MissingParent<!> {
  @Binds val String.bind: CharSequence
}

interface UnresolvedAncestor : <!UNRESOLVED_REFERENCE!>MissingAncestor<!>

@ContributesTo(AppScope::class)
interface UnresolvedAncestorChild : UnresolvedAncestor {
  @Binds val String.bind: CharSequence
}
