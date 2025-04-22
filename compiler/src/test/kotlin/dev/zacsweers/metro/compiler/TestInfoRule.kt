// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.junit.rules.TestWatcher
import org.junit.runner.Description

class TestInfoRule : TestWatcher() {
  private var _currentClassName: String? = null
  val currentClassName: String
    get() = _currentClassName!!

  private var _currentMethodName: String? = null
  val currentMethodName: String
    get() = _currentMethodName!!

  override fun starting(description: Description) {
    _currentClassName = description.className
    _currentMethodName = description.methodName
  }

  override fun finished(description: Description) {
    _currentClassName = null
    _currentMethodName = null
  }
}
