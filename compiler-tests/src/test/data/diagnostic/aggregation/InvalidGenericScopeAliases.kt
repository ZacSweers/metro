// RENDER_DIAGNOSTICS_FULL_TEXT
// MAX_COMPILER_VERSION: 2.5.0-dev-7307
abstract class Scope<T>

typealias MissingOuterScope = <!UNRESOLVED_REFERENCE!>MissingOuter<!><String>

@ContributesTo(<!ANNOTATION_ARGUMENT_MUST_BE_CONST!>MissingOuterScope::class<!>)
interface MissingOuterBindings

// Classifier lookup must leave the original generic arguments available for normal type checking.
typealias MissingArgumentScope = Scope<<!UNRESOLVED_REFERENCE!>MissingType<!>>

@ContributesTo(MissingArgumentScope::class)
interface MissingArgumentBindings

typealias RecursiveScope = <!RECURSIVE_TYPEALIAS_EXPANSION!>RecursiveScope<!>

@ContributesTo(<!ANNOTATION_ARGUMENT_MUST_BE_CONST!>RecursiveScope::class<!>)
interface RecursiveBindings
