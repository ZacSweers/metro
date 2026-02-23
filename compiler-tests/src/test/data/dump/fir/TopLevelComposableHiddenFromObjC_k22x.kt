// ENABLE_TOP_LEVEL_FUNCTION_INJECTION
// COMPILER_VERSION: 2.2

// FILE: Composable.kt
package androidx.compose.runtime

annotation class Composable

annotation class Stable

// FILE: main.kt
import androidx.compose.runtime.Composable

@Composable
@Inject
fun ComposableApp(message: String) {
}

@Inject
fun NonComposableApp(message: String) {
}
