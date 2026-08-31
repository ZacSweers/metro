// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicLong

/** Tracks the newest asynchronous graph validation request. */
@Service(Service.Level.PROJECT)
internal class MetroValidationRequestService {
  private val latestRequest = AtomicLong()

  fun beginRequest(): Long = latestRequest.incrementAndGet()

  fun isLatest(requestToken: Long): Boolean = latestRequest.get() == requestToken
}
