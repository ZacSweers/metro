// RENDER_DIAGNOSTICS_FULL_TEXT

// Star projection is not allowed
@DefaultBinding<<!DEFAULT_BINDING_ERROR!>*<!>>
interface Base1

// Placeholder is not allowed (or legal anyway)
//@DefaultBinding<_>
//interface Base2

// Valid usage for comparison
@DefaultBinding<Base3>
interface Base3
