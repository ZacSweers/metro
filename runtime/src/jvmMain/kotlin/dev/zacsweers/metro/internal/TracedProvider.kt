// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider

/**
 * Provider decorator used by Metro-generated code when runtime tracing is enabled.
 *
 * Each invocation opens a trace section with [name], delegates to [provider], and closes the
 * section even if the delegate throws.
 */
public class TracedProvider<T>(
  private val traceContext: MetroTraceContext,
  private val name: String,
  private val provider: Provider<T>,
) : Provider<T> {
  override fun invoke(): T {
    return traceContext.trace(name) { provider.invoke() }
  }
}
