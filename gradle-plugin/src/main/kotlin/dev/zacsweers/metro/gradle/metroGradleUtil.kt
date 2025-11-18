package dev.zacsweers.metro.gradle

import java.util.Locale

internal fun String.capitalizeUS() = replaceFirstChar {
  if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
}
