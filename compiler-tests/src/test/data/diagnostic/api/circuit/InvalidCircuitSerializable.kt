// ENABLE_CIRCUIT
// MIN_COMPILER_VERSION: 2.3.20
// RENDER_DIAGNOSTICS_FULL_TEXT

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable

<!CIRCUIT_SERIALIZABLE_ERROR!>@CircuitSerializable(AppScope::class)<!>
interface InterfaceScreen : Screen

@CircuitSerializable(AppScope::class)
<!CIRCUIT_SERIALIZABLE_ABSTRACT_ERROR!>abstract<!> class AbstractScreen : Screen

@CircuitSerializable(AppScope::class)
class GenericScreen<!CIRCUIT_SERIALIZABLE_TYPE_PARAMETERS_ERROR!><T><!> : Screen

class Outer {
  @CircuitSerializable(AppScope::class)
  <!CIRCUIT_SERIALIZABLE_INNER_ERROR!>inner<!> class InnerScreen : Screen
}

class PrivateOwner {
  @CircuitSerializable(AppScope::class)
  <!CIRCUIT_SERIALIZABLE_VISIBILITY_ERROR!>private<!> class InaccessibleScreen : Screen
}

<!CIRCUIT_SERIALIZABLE_ERROR!>@CircuitSerializable(AppScope::class)<!>
class WrongSupertype

<!CIRCUIT_SERIALIZABLE_ERROR!>@CircuitSerializable(AppScope::class)<!>
enum class EnumScreen : Screen {
  VALUE,
}

<!CIRCUIT_SERIALIZABLE_ERROR!>@CircuitSerializable(AppScope::class)<!>
annotation class AnnotatedScreen
