// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.MetroAnnotations
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/** FIR declaration attributes used by Metro to tag generated declarations with metadata. */
internal object MetroFirAttributes {
  /**
   * Marks a class returned by a
   * [MetroFirDeclarationGenerationExtension][dev.zacsweers.metro.compiler.api.fir.MetroFirDeclarationGenerationExtension].
   *
   * This is used to prevent contribution provider generation for these classes, since FIR cannot
   * generate top-level classes from other generated top-level classes.
   */
  object IsExtensionGenerated : FirDeclarationDataKey()

  /**
   * Marks a callable an extension attached directly to a generated class. Kotlin can omit these
   * members from metadata, so Metro publishes their bindings through a mirror.
   */
  object IsAttachedExtensionMember : FirDeclarationDataKey()

  /**
   * Caches the full-kinds [MetroAnnotations] resolution for a declaration. Only populated through
   * the `context(CheckerContext)` overload of `metroAnnotations` as this isn't safe to cache in FIR
   * gen..
   */
  object MetroAnnotationsCache : FirDeclarationDataKey()
}

internal var FirClass.isExtensionGenerated: Boolean? by
  FirDeclarationDataRegistry.data(MetroFirAttributes.IsExtensionGenerated)
  private set

/** Tags the class and its initial members before Kotlin invokes the callable generators. */
@OptIn(DirectDeclarationsAccess::class)
internal fun FirClass.markAsExtensionGenerated() {
  isExtensionGenerated = true

  for (member in declarations) {
    if (member is FirCallableDeclaration) {
      member.isAttachedExtensionMember = true
    }
  }
}

internal var FirCallableDeclaration.isAttachedExtensionMember: Boolean? by
  FirDeclarationDataRegistry.data(MetroFirAttributes.IsAttachedExtensionMember)
  private set

internal val IrSimpleFunction.isAttachedExtensionMember: Boolean
  get() {
    val source = correspondingPropertySymbol?.owner?.metadata ?: metadata
    val member = (source as? FirMetadataSource)?.fir as? FirCallableDeclaration
    return member?.isAttachedExtensionMember == true
  }

internal val IrClass.isExtensionGenerated: Boolean
  get() = (metadata as? FirMetadataSource.Class)?.fir?.isExtensionGenerated == true

internal var FirDeclaration.metroAnnotationsCache: MetroAnnotations<MetroFirAnnotation>? by
  FirDeclarationDataRegistry.data(MetroFirAttributes.MetroAnnotationsCache)
