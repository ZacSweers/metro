// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** Files read by class discovery, including objects without Metro annotations. */
internal class SourceClassDependencies
private constructor(
  private val files: Map<VirtualFile, FileStamp>,
  val owners: Map<VirtualFile, Set<VirtualFile>>,
  private val unresolvedOwners: Map<ClassId, Set<VirtualFile>>,
  private val errorTypeOwners: Set<VirtualFile>,
) {
  /** Finds newly declared types that can satisfy requests missing from the previous snapshot. */
  fun ownersForNewDeclarations(file: KtFile): Set<VirtualFile> {
    if (unresolvedOwners.isEmpty() && errorTypeOwners.isEmpty()) return emptySet()
    val result = linkedSetOf<VirtualFile>()
    file.accept(
      object : KtTreeVisitorVoid() {
        override fun visitClassOrObject(classOrObject: KtClassOrObject) {
          ProgressManager.checkCanceled()
          result += errorTypeOwners
          result += unresolvedOwners[classOrObject.getClassId()].orEmpty()
          super.visitClassOrObject(classOrObject)
        }
      }
    )
    return result
  }

  /** Called inside the snapshot read action before reusing derived bindings. */
  fun isCurrent(): Boolean {
    for ((file, stamp) in files) {
      ProgressManager.checkCanceled()
      if (!file.isValid || stamp.pointer.element?.modificationStamp != stamp.modificationStamp) {
        return false
      }
    }
    return true
  }

  private class FileStamp(
    val pointer: SmartPsiElementPointer<PsiFile>,
    val modificationStamp: Long,
  )

  /** Collects dependency owners during one source or binary discovery pass. */
  class Builder(
    private val pointers: SmartPointerManager,
    previous: SourceClassDependencies = EMPTY,
  ) {
    private val files = previous.files.toMutableMap()
    private val owners = previous.owners.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
    private val unresolvedOwners =
      previous.unresolvedOwners.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
    private val errorTypeOwners = previous.errorTypeOwners.toMutableSet()

    /** An error type has no reliable class ID. Retry its owner when a class declaration appears. */
    fun recordErrorType(owner: VirtualFile?) {
      if (owner != null) errorTypeOwners += owner
    }

    fun recordUnresolved(classId: ClassId, owner: VirtualFile?) {
      if (owner != null) unresolvedOwners.getOrPut(classId) { linkedSetOf() } += owner
    }

    fun record(file: PsiFile, owner: VirtualFile?) {
      val virtualFile = file.virtualFile ?: return
      files[virtualFile] =
        FileStamp(pointers.createSmartPsiElementPointer(file), file.modificationStamp)
      if (owner != null) owners.getOrPut(virtualFile) { linkedSetOf() } += owner
    }

    fun include(dependencies: SourceClassDependencies) {
      files += dependencies.files
      for ((file, sources) in dependencies.owners) {
        owners.getOrPut(file) { linkedSetOf() } += sources
      }
      for ((classId, sources) in dependencies.unresolvedOwners) {
        unresolvedOwners.getOrPut(classId) { linkedSetOf() } += sources
      }
      errorTypeOwners += dependencies.errorTypeOwners
    }

    fun build(): SourceClassDependencies =
      SourceClassDependencies(
        files.toMap(),
        owners.mapValues { it.value.toSet() },
        unresolvedOwners.mapValues { it.value.toSet() },
        errorTypeOwners.toSet(),
      )
  }

  companion object {
    val EMPTY = SourceClassDependencies(emptyMap(), emptyMap(), emptyMap(), emptySet())
  }
}
