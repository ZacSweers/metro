// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

abstract class GitCommitValueSource : ValueSource<String, GitCommitValueSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val projectDirectory: DirectoryProperty
  }

  override fun obtain(): String? {
    return try {
      readGitRepoCommit(parameters.projectDirectory.get().asFile.toPath())
    } catch (_: Exception) {
      null
    }
  }
}

private fun isGitHash(hash: String): Boolean {
  if (hash.length != 40) {
    return false
  }

  return hash.all { it in '0'..'9' || it in 'a'..'f' }
}

// Impl from https://gist.github.com/madisp/6d753bde19e278755ec2b69ccfc17114
private fun readGitRepoCommit(projectDirectory: Path): String? {
  val gitDirectory = projectDirectory.resolve(".git")
  val head = gitDirectory.resolve("HEAD")
  if (!head.exists()) {
    return null
  }

  val headContents = head.readText(Charsets.UTF_8).lowercase(Locale.US).trim()

  if (isGitHash(headContents)) {
    return headContents
  }

  if (!headContents.startsWith("ref:")) {
    return null
  }

  val headRef = headContents.removePrefix("ref:").trim()
  val headFile = gitDirectory.resolve(headRef)
  if (!headFile.exists()) {
    return null
  }

  return headFile.readText(Charsets.UTF_8).trim().takeIf { isGitHash(it) }
}
