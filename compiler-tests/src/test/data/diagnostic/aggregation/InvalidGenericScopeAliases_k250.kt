// RENDER_DIAGNOSTICS_FULL_TEXT
// MIN_COMPILER_VERSION: 2.5.0-dev-10106

abstract class Scope<T>

typealias MissingOuterScope = <!UNRESOLVED_REFERENCE!>MissingOuter<!><String>

@ContributesTo(MissingOuterScope::class)
interface MissingOuterBindings

// Classifier lookup must leave the original generic arguments available for normal type checking.
typealias MissingArgumentScope = Scope<<!UNRESOLVED_REFERENCE!>MissingType<!>>

@ContributesTo(MissingArgumentScope::class)
interface MissingArgumentBindings

typealias RecursiveScope = <!RECURSIVE_TYPEALIAS_EXPANSION!>RecursiveScope<!>

@ContributesTo(RecursiveScope::class)
interface RecursiveBindings
