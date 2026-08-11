// ENABLE_CIRCUIT
// ENABLE_SERIALIZATION
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.screen.restoreScreen
import com.slack.circuit.serialization.CircuitSerializable
import com.slack.circuit.serialization.CircuitSerializerRegistration
import com.slack.circuit.serialization.SerializableCircuitSaver

typealias TypeAliasSerializationScope = AppScope

@CircuitSerializable(TypeAliasSerializationScope::class)
data class TypeAliasSerializableScreen(val value: String) : Screen

@BindingContainer
interface CircuitSerializationBindings {
  @Multibinds(allowEmpty = true)
  fun registrations(): Set<CircuitSerializerRegistration>
}

@DependencyGraph(
  AppScope::class,
  bindingContainers = [CircuitSerializationBindings::class],
)
interface AppGraph {
  val registrations: Set<CircuitSerializerRegistration>
}

fun box(): String {
  val registrations = createGraph<AppGraph>().registrations
  if (registrations.size != 1) {
    return "FAIL: expected one registration but got ${registrations.size}"
  }

  val saver = SerializableCircuitSaver(registrations)
  val screen = TypeAliasSerializableScreen("aliased")
  val saved = saver.save(screen) ?: return "FAIL: screen was not saved"
  val restored = saver.restoreScreen<TypeAliasSerializableScreen>(saved)
  return if (restored == screen) "OK" else "FAIL: screen was not restored"
}
