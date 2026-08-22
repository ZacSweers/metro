// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.SuspendProvider
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalMetroCoroutinesApi::class)
class SwitchingSuspendProviderTest : MetroCompilerTest() {

  @Test
  fun `suspend switching provider resumes through chunked routing`() = runTest {
    val result =
      compile(
        source(
          """
          import kotlin.coroutines.Continuation
          import kotlin.coroutines.resume
          import kotlin.coroutines.suspendCoroutine

          class Message(val value: String)

          object SuspensionGate {
            private val suspended = CountDownLatch(1)
            private lateinit var continuation: Continuation<String>

            suspend fun awaitValue(): String = suspendCoroutine {
              continuation = it
              suspended.countDown()
            }

            fun awaitSuspension(): Boolean = suspended.await(5, TimeUnit.SECONDS)

            fun resume(value: String): Boolean {
              continuation.resume(value)
              return true
            }
          }

          @DependencyGraph
          interface ExampleGraph {
            val messageProvider: SuspendProvider<Message>
            val valueProvider: SuspendProvider<String>

            suspend fun message(): Message

            @Provides
            suspend fun provideValue(): String = SuspensionGate.awaitValue()

            @Provides
            fun provideMessage(value: String): Message = Message(value)
          }
          """
            .trimIndent()
        ),
        options =
          metroOptions
            .toBuilder()
            .enableSuspendProviders(true)
            .apply {
              enableSwitchingProviders = true
              statementsPerInitFun = 1
            }
            .build(),
      )

    val graph = result.ExampleGraph.generatedImpl().createGraphWithNoArgs()
    val gate =
      result.classLoader.loadClass("test.SuspensionGate").getDeclaredField("INSTANCE").get(null)
    val messageProvider = graph.callProperty<SuspendProvider<Any>>("messageProvider")
    val pendingMessage = async(Dispatchers.Default) { messageProvider() }

    assertThat(gate.invokeInstanceMethod<Boolean>("awaitSuspension")).isTrue()
    assertThat(pendingMessage.isCompleted).isFalse()

    assertThat(gate.invokeInstanceMethod<Boolean>("resume", "resumed")).isTrue()

    val message = pendingMessage.await()
    assertThat(message.callProperty<String>("value")).isEqualTo("resumed")
  }
}
