// ENABLE_CIRCUIT
// ENABLE_SERIALIZATION
// MIN_COMPILER_VERSION: 2.3.20
// RENDER_DIAGNOSTICS_FULL_TEXT

// FILE: FlattenedNames.kt

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable

class Navigation {
  @CircuitSerializable(AppScope::class)
  data object <!CIRCUIT_SERIALIZABLE_ERROR!>SettingsScreen<!> : Screen
}

@CircuitSerializable(AppScope::class)
data object <!CIRCUIT_SERIALIZABLE_ERROR!>Navigation_SettingsScreen<!> : Screen

// FILE: ExistingDeclaration.kt

package collision.existing

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable

class ExistingScreenCircuitSerializerRegistration

@CircuitSerializable(AppScope::class)
data object <!CIRCUIT_SERIALIZABLE_ERROR!>ExistingScreen<!> : Screen
