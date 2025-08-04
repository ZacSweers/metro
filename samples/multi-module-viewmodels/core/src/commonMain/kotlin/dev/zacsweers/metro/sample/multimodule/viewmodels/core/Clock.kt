// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.multimodule.viewmodels.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun interface Clock {
  fun nowMilliseconds(): Long
}

fun Clock.formatNow(): String =
  DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S")
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(nowMilliseconds()))
