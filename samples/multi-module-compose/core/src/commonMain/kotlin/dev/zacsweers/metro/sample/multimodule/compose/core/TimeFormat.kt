@file:OptIn(ExperimentalTime::class)

package dev.zacsweers.metro.sample.multimodule.compose.core

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

fun Clock.formatNow(): String = DateTimeFormatter
  .ofPattern("yyyy-MM-dd HH:mm:ss.S")
  .withLocale(Locale.getDefault())
  .withZone(ZoneId.systemDefault())
  .format(now().toJavaInstant())
