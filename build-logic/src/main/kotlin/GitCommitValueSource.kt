// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
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

// Impl from https://gist.github.com/madisp/6d753bde19e278755ec2b69ccfc17114, extended to handle
// worktree gitdir files and packed refs.
private fun readGitRepoCommit(projectDirectory: Path): String? {
  val gitDirectory = resolveGitDirectory(projectDirectory) ?: return null
  val head = gitDirectory.resolve("HEAD")
  if (!head.exists()) {
    return null
  }

  // Only lowercase when validating hash content. Ref paths are case sensitive on disk.
  val headContents = head.readText(Charsets.UTF_8).trim()
  val headHash = headContents.lowercase(Locale.US)
  if (isGitHash(headHash)) {
    return headHash
  }

  if (!headContents.startsWith("ref:")) {
    return null
  }

  val headRef = headContents.removePrefix("ref:").trim()
  // A linked worktree keeps HEAD in its private dir while branch refs live in the shared common
  // dir, so check both loose locations before the packed-refs fallback.
  val commonDirectory = resolveCommonDirectory(gitDirectory)
  for (refsRoot in setOf(gitDirectory, commonDirectory)) {
    val headFile = refsRoot.resolve(headRef)
    if (headFile.exists()) {
      return headFile.readText(Charsets.UTF_8).trim().lowercase(Locale.US).takeIf { isGitHash(it) }
    }
  }
  return readPackedRef(commonDirectory, headRef)
}

/** Handles both a plain `.git` directory and a worktree's `gitdir: <path>` pointer file. */
private fun resolveGitDirectory(projectDirectory: Path): Path? {
  val gitPath = projectDirectory.resolve(".git")
  if (gitPath.isDirectory()) {
    return gitPath
  }
  if (!gitPath.isRegularFile()) {
    return null
  }
  val pointer = gitPath.readText(Charsets.UTF_8).trim()
  if (!pointer.startsWith("gitdir:")) {
    return null
  }
  val target = projectDirectory.resolve(pointer.removePrefix("gitdir:").trim()).normalize()
  // Worktree gitdirs hold their own HEAD. Shared refs stay in the common dir it points into.
  return target.takeIf { it.exists() }
}

/** The shared common dir holding refs for every worktree of a repository. */
private fun resolveCommonDirectory(gitDirectory: Path): Path {
  val commonDirFile = gitDirectory.resolve("commondir")
  if (!commonDirFile.isRegularFile()) {
    return gitDirectory
  }
  return gitDirectory.resolve(commonDirFile.readText(Charsets.UTF_8).trim()).normalize()
}

/** Refs disappear from loose files after `git gc` packs them into `packed-refs`. */
private fun readPackedRef(refsRoot: Path, ref: String): String? {
  val packedRefs = refsRoot.resolve("packed-refs")
  if (!packedRefs.exists()) {
    return null
  }
  for (line in packedRefs.readText(Charsets.UTF_8).lineSequence()) {
    if (line.isBlank() || line.startsWith("#") || line.startsWith("^")) {
      continue
    }
    val separator = line.indexOf(' ')
    if (separator != 40) {
      continue
    }
    if (line.substring(separator + 1).trim() != ref) {
      continue
    }
    return line.substring(0, separator).lowercase(Locale.US).takeIf { isGitHash(it) }
  }
  return null
}
