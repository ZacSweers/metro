// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

// CHANGELOG.md underlines release titles and uses ### for sections within each release.
private val releaseHeading = Regex("""(?m)^(\*\*Unreleased\*\*|\d+\.\d+\.\d+\S*)\r?\n-{3,}\r?$""")
private val sectionStart = Regex("""(?m)(?=^### )""")

/** Selects Markdown from one changelog release before the IDEA build renders it as HTML. */
fun ideaReleaseNotes(changelog: String, version: String): String {
  val snapshot = version.contains("SNAPSHOT")
  val title =
    if (snapshot) {
      "**Unreleased**"
    } else {
      version
    }
  val release =
    releaseHeading.findAll(changelog).firstOrNull { it.groupValues[1] == title } ?: return ""
  val end = release.next()?.range?.first ?: changelog.length
  val body = changelog.substring(release.range.last + 1, end)
  val notes = body.split(sectionStart).mapNotNull(::ideaSection).joinToString("\n\n")

  // The Marketplace can't resolve relative links to the repository docs.
  val ref =
    if (snapshot) {
      "main"
    } else {
      version
    }
  return notes.replace("](docs/", "](https://github.com/ZacSweers/metro/blob/$ref/docs/")
}

/** Keep the full contributor credits. Elsewhere, keep tagged entries and their indented details. */
private fun ideaSection(section: String): String? {
  val heading = section.lineSequence().first()
  if (heading == "### Contributors") {
    return section.trimEnd()
  }

  var keep = false
  val entries = buildString {
    for (line in section.lineSequence()) {
      if (line.isNotEmpty() && !line.first().isWhitespace()) {
        keep = line.startsWith("- **[core]**") || line.startsWith("- **[IDEA]**")
      }

      if (keep) {
        appendLine(line)
      }
    }
  }
    .trimEnd()
  if (entries.isEmpty()) {
    return null
  }

  return if (heading.startsWith("### ")) {
    "$heading\n\n$entries"
  } else {
    entries
  }
}
