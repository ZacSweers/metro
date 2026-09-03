// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** Files read by class discovery, including objects without Metro annotations. */
internal class SourceClassDependencies
private constructor(
  private val files: Map<VirtualFile, FileStamp>,
  val owners: Map<VirtualFile, Set<VirtualFile>>,
  private val unresolvedOwners: Map<ClassId, Set<MissingTypeOwner>>,
  private val errorTypeOwners: Map<Name?, Set<MissingTypeOwner>>,
) {
  /**
   * Finds available declarations whose names and module visibility can satisfy missing requests.
   */
  @OptIn(KaPlatformInterface::class)
  fun ownersForAvailableDeclarations(file: KtFile): Set<VirtualFile> {
    if (unresolvedOwners.isEmpty() && errorTypeOwners.isEmpty()) return emptySet()
    val result = linkedSetOf<VirtualFile>()
    val visibleModules = hashMapOf<KaModule, Boolean>()
    fun addVisibleOwners(owners: Set<MissingTypeOwner>?) {
      for (owner in owners.orEmpty()) {
        ProgressManager.checkCanceled()
        val visible =
          visibleModules.getOrPut(owner.module) {
            KaResolutionScope.forModule(owner.module).contains(file)
          }
        if (visible) result += owner.file
      }
    }
    file.accept(
      object : KtTreeVisitorVoid() {
        override fun visitClassOrObject(classOrObject: KtClassOrObject) {
          ProgressManager.checkCanceled()
          val classId = classOrObject.getClassId()
          if (classId != null) {
            addVisibleOwners(errorTypeOwners[classId.shortClassName])
            addVisibleOwners(errorTypeOwners[null])
            addVisibleOwners(unresolvedOwners[classId])
          }
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

  /** The graph use site owns both the retry and the module from which the type must be visible. */
  private data class MissingTypeOwner(val file: VirtualFile, val module: KaModule)

  /** Collects dependency owners during one source or binary discovery pass. */
  class Builder(
    private val pointers: SmartPointerManager,
    previous: SourceClassDependencies = EMPTY,
  ) {
    private val files = previous.files.toMutableMap()
    private val owners = previous.owners.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
    private val unresolvedOwners =
      previous.unresolvedOwners.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
    private val errorTypeOwners =
      previous.errorTypeOwners.mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }

    /**
     * Captures missing names at every argument depth and reports whether the request has errors.
     */
    @OptIn(KaPlatformInterface::class)
    fun recordErrorTypes(
      type: KaTypeSnapshot,
      context: KtElement,
      source: SmartPsiElementPointer<out PsiElement>,
    ): Boolean {
      if (!type.isError && type.typeArguments.isEmpty()) return false
      var names: MutableSet<Name?>? = null
      val pending = ArrayDeque<KaTypeSnapshot>()
      pending += type
      while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val current = pending.removeLast()
        if (current.isError) {
          val missingNames = names ?: linkedSetOf<Name?>().also { names = it }
          missingNames += current.unresolvedClassName
        }
        for (argument in current.typeArguments) argument.type?.let(pending::addLast)
      }
      val missingNames = names ?: return false
      val ownerFile = context.containingFile?.virtualFile ?: return true
      val module = KaModuleProvider.getModule(context.project, context, useSiteModule = null)
      val owner = MissingTypeOwner(ownerFile, module)
      val sourceFile = source.element?.containingFile ?: context.containingFile
      val imports = (sourceFile as? KtFile)?.importDirectives.orEmpty()
      for (name in missingNames) {
        // Inferred errors can lack a class name. Their source may call another erroneous function,
        // so keep a module-scoped retry until Analysis API can identify the missing declaration.
        errorTypeOwners.getOrPut(name) { linkedSetOf() } += owner
        if (name == null) continue
        for (directive in imports) {
          if (directive.aliasName != name.asString()) continue
          val importedName = directive.importedFqName?.shortName() ?: continue
          errorTypeOwners.getOrPut(importedName) { linkedSetOf() } += owner
        }
      }
      return true
    }

    fun recordUnresolved(classId: ClassId, owner: VirtualFile?, module: KaModule) {
      if (owner != null) {
        unresolvedOwners.getOrPut(classId) { linkedSetOf() } += MissingTypeOwner(owner, module)
      }
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
      for ((name, sources) in dependencies.errorTypeOwners) {
        errorTypeOwners.getOrPut(name) { linkedSetOf() } += sources
      }
    }

    fun build(): SourceClassDependencies =
      SourceClassDependencies(
        files.toMap(),
        owners.mapValues { it.value.toSet() },
        unresolvedOwners.mapValues { it.value.toSet() },
        errorTypeOwners.mapValues { it.value.toSet() },
      )
  }

  companion object {
    val EMPTY = SourceClassDependencies(emptyMap(), emptyMap(), emptyMap(), emptyMap())
  }
}
