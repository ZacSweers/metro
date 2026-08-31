// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.progress.ProgressManager

/** Checks cancellation every 64 work items. Pass a counter that advances through the operation. */
internal fun checkCanceledEvery(workIndex: Int) {
  if (workIndex and CANCELLATION_CHECK_MASK == 0) {
    ProgressManager.checkCanceled()
  }
}

private const val CANCELLATION_CHECK_MASK = 0x3f
