// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.jetbrains.kotlin.test.ExecutionListenerBasedDisposableProvider
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.services.ArtifactsProvider
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.artifactsProvider
import org.junit.jupiter.api.AfterEach

/**
 * The first-party kotlin compiler test framework appears to have some sort of memory leak involving
 * [TestServices] and [ArtifactsProvider]. I haven't been able to figure out why these aren't
 * cleared up between tests, but this workaround goes in and reflectively clears the cached
 * artifacts after each test.
 */
@OptIn(ExperimentalAtomicApi::class)
interface LeakPatchingTest {
  @AfterEach
  fun tearDown() {
    try {
      val artifactsProvider = getTestServices()?.artifactsProvider ?: return
      (FIELD.get(artifactsProvider) as MutableMap<*, *>).clear()
    } catch (_: Exception) {
      // May not be registered
    }
  }

  fun getTestServices(): TestServices?

  fun setTestServices(testServices: TestServices)

  fun TestConfigurationBuilder.registerLeakPatcher() {
    useAdditionalService {
      setTestServices(it)
      ExecutionListenerBasedDisposableProvider()
    }
  }

  companion object {
    private val FIELD =
      ArtifactsProvider::class.java.getDeclaredField("artifactsByModule").also {
        it.isAccessible = true
      }

    operator fun invoke(): LeakPatchingTest =
      object : LeakPatchingTest {
        private val atomicReference = AtomicReference<TestServices?>(null)

        override fun getTestServices(): TestServices? {
          return atomicReference.load()
        }

        override fun setTestServices(testServices: TestServices) {
          atomicReference.store(testServices)
        }
      }
  }
}
