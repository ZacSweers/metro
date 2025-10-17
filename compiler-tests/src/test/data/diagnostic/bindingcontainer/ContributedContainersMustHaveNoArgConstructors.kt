// RENDER_DIAGNOSTICS_FULL_TEXT

@ContributesTo(AppScope::class)
@BindingContainer
class BadContainer1 <!METRO_DECLARATION_VISIBILITY_ERROR!>private<!> constructor()

@ContributesTo(AppScope::class)
@BindingContainer
class <!BINDING_CONTAINER_ERROR!>BadContainer2<!>(private val foo: String)

