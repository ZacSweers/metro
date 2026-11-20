// RENDER_DIAGNOSTICS_FULL_TEXT

package test

// Ok
@DependencyGraph interface InterfaceGraph
@DependencyGraph abstract class AbstractClassGraph

// Not ok
@DependencyGraph class <!METRO_DECLARATION_ERROR!>FinalClassGraph<!>
@DependencyGraph open class <!METRO_DECLARATION_ERROR!>OpenClassGraph<!>
@DependencyGraph annotation class <!METRO_DECLARATION_ERROR!>AnnotationClassGraph<!>
@DependencyGraph enum class <!METRO_DECLARATION_ERROR!>EnumClassGraph<!>
@DependencyGraph sealed class <!METRO_DECLARATION_ERROR!>SealedClassGraph<!>
@DependencyGraph sealed interface <!METRO_DECLARATION_ERROR!>SealedInterfaceGraph<!>
@DependencyGraph <!METRO_DECLARATION_VISIBILITY_ERROR!>private<!> interface PrivateGraph
@DependencyGraph abstract class PrivateConstructorGraph <!METRO_DECLARATION_VISIBILITY_ERROR!>private<!> constructor()
