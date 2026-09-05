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
  private val contextFiles: Map<VirtualFile, FileStamp>,
  private val consistent: Boolean,
  val owners: Map<VirtualFile, Set<VirtualFile>>,
  private val unresolvedOwners: Map<ClassId, Set<MissingTypeOwner>>,
  private val errorTypeOwners: Map<Name?, Set<MissingTypeOwner>>,
) {
  /**
   * Finds available declarations whose names and module visibility can satisfy missing requests.
   */
  @OptIn(KaPlatformInterface::class)
  fun ownersForAvailableDeclarations(file: KtFile): Set<VirtualFile> {
    if (unresolvedOwners.isEmpty() && errorTypeOwners.isEmpty()) {
      return emptySet()
    }
    val result = linkedSetOf<VirtualFile>()
    val visibleModules = hashMapOf<KaModule, Boolean>()
    fun addVisibleOwners(owners: Set<MissingTypeOwner>?) {
      for (owner in owners.orEmpty()) {
        ProgressManager.checkCanceled()
        val visible =
          visibleModules.getOrPut(owner.module) {
            KaResolutionScope.forModule(owner.module).contains(file)
          }
        if (visible) {
          result += owner.file
        }
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
    if (!consistent) {
      return false
    }
    for (stamps in listOf(files, contextFiles)) {
      for ((file, stamp) in stamps) {
        ProgressManager.checkCanceled()
        if (!file.isValid || stamp.pointer.element?.modificationStamp != stamp.modificationStamp) {
          return false
        }
      }
    }
    return true
  }

  /**
   * Drops temporary read anchors after the caller validates the whole capture. Cache keys retain
   * request meaning; declaration files and missing-type owners remain dependencies of cached work.
   * A file used in both roles keeps its declaration stamp, including any detected conflict.
   */
  fun withoutReadContexts(): SourceClassDependencies {
    if (contextFiles.isEmpty()) {
      return this
    }
    return SourceClassDependencies(
      files,
      emptyMap(),
      consistent,
      owners,
      unresolvedOwners,
      errorTypeOwners,
    )
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
    private val contextFiles = previous.contextFiles.toMutableMap()
    private var consistent = previous.consistent
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
      if (!type.isError && type.typeArguments.isEmpty()) {
        return false
      }
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
        if (name == null) {
          continue
        }
        for (directive in imports) {
          if (directive.aliasName != name.asString()) {
            continue
          }
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

    /** Records a declaration whose captured content must remain valid while a cache is reused. */
    fun record(file: PsiFile, owner: VirtualFile?) {
      val virtualFile = file.virtualFile ?: return
      includeStamp(
        virtualFile,
        FileStamp(pointers.createSmartPsiElementPointer(file), file.modificationStamp),
        context = false,
      )
      if (owner != null) {
        owners.getOrPut(virtualFile) { linkedSetOf() } += owner
      }
    }

    /** Keeps a request anchor stable across independent reads without adding a cache dependency. */
    fun recordContext(file: PsiFile) {
      val virtualFile = file.virtualFile ?: return
      includeStamp(
        virtualFile,
        FileStamp(pointers.createSmartPsiElementPointer(file), file.modificationStamp),
        context = true,
      )
    }

    /** Keeps conflicting read generations invalid until the entire snapshot is rebuilt. */
    fun include(dependencies: SourceClassDependencies) {
      consistent = consistent && dependencies.consistent
      for ((file, stamp) in dependencies.files) {
        includeStamp(file, stamp, context = false)
      }
      for ((file, stamp) in dependencies.contextFiles) {
        includeStamp(file, stamp, context = true)
      }
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

    private fun includeStamp(file: VirtualFile, stamp: FileStamp, context: Boolean) {
      val target =
        if (context) {
          contextFiles
        } else {
          files
        }
      val other =
        if (context) {
          files
        } else {
          contextFiles
        }
      val previous = target.putIfAbsent(file, stamp)
      val otherStamp = other[file]
      val sameKindConflict =
        previous != null && previous.modificationStamp != stamp.modificationStamp
      val otherKindConflict =
        otherStamp != null && otherStamp.modificationStamp != stamp.modificationStamp
      if (sameKindConflict || otherKindConflict) {
        consistent = false
      }
    }

    fun build(): SourceClassDependencies =
      SourceClassDependencies(
        files.toMap(),
        contextFiles.toMap(),
        consistent,
        owners.mapValues { it.value.toSet() },
        unresolvedOwners.mapValues { it.value.toSet() },
        errorTypeOwners.mapValues { it.value.toSet() },
      )
  }

  companion object {
    val EMPTY =
      SourceClassDependencies(emptyMap(), emptyMap(), true, emptyMap(), emptyMap(), emptyMap())
  }
}
