// ENABLE_CIRCUIT
// MIN_COMPILER_VERSION: 2.3.20
// RENDER_DIAGNOSTICS_FULL_TEXT

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.serialization.CircuitSerializable

<!CIRCUIT_SERIALIZABLE_ERROR!>@CircuitSerializable(AppScope::class)<!>
data class MissingSerializerScreen(val id: Long) : Screen
