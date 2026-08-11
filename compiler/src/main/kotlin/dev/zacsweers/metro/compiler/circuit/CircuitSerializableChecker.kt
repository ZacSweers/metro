// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_SERIALIZABLE_ABSTRACT_ERROR
import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_SERIALIZABLE_ERROR
import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_SERIALIZABLE_INNER_ERROR
import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_SERIALIZABLE_TYPE_PARAMETERS_ERROR
import dev.zacsweers.metro.compiler.circuit.CircuitDiagnostics.CIRCUIT_SERIALIZABLE_VISIBILITY_ERROR
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroFirTypeResolver
import dev.zacsweers.metro.compiler.fir.allSessions
import dev.zacsweers.metro.compiler.fir.annotationsIn
import dev.zacsweers.metro.compiler.fir.classArgument
import dev.zacsweers.metro.compiler.fir.implements
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isInner
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider

/** Validates classes and objects annotated with `@CircuitSerializable`. */
internal object CircuitSerializableClassChecker : FirClassChecker(MppCheckerKind.Common) {

  context(context: CheckerContext, reporter: DiagnosticReporter)
  override fun check(declaration: FirClass) {
    val session = context.session
    val annotation =
      declaration.annotationsIn(session, setOf(CircuitClassIds.CircuitSerializable)).firstOrNull()
        ?: return

    // Platform FIR sessions contain both declarations. The expect declaration owns validation and
    // generation, while Kotlin validates expect/actual annotation agreement.
    if (declaration.symbol.rawStatus.isActual) return

    val annotationSource = annotation.source ?: declaration.source ?: return
    val declarationSource = declaration.source ?: annotationSource
    val visibility = declaration.effectiveVisibility.toVisibility()
    if (visibility != Visibilities.Public && visibility != Visibilities.Internal) {
      reporter.reportOn(
        declarationSource,
        CIRCUIT_SERIALIZABLE_VISIBILITY_ERROR,
        "@CircuitSerializable is not applicable to private, protected, or local declarations.",
      )
      return
    }

    if (declaration.classKind == ClassKind.INTERFACE) {
      reporter.reportOn(
        annotationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "@CircuitSerializable is not applicable to interfaces.",
      )
      return
    }

    if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.OBJECT) {
      reporter.reportOn(
        annotationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "@CircuitSerializable is only applicable to classes and objects.",
      )
      return
    }

    if (
      declaration.symbol.rawStatus.modality == Modality.ABSTRACT &&
        !declaration.symbol.rawStatus.isExpect
    ) {
      reporter.reportOn(
        declarationSource,
        CIRCUIT_SERIALIZABLE_ABSTRACT_ERROR,
        "@CircuitSerializable is not applicable to abstract classes.",
      )
      return
    }

    if (declaration.isInner) {
      reporter.reportOn(
        declarationSource,
        CIRCUIT_SERIALIZABLE_INNER_ERROR,
        "@CircuitSerializable is not applicable to inner classes.",
      )
      return
    }

    if (declaration.typeParameters.isNotEmpty()) {
      reporter.reportOn(
        declarationSource,
        CIRCUIT_SERIALIZABLE_TYPE_PARAMETERS_ERROR,
        "@CircuitSerializable is not applicable to generic classes.",
      )
      return
    }

    val implementsSupportedType =
      declaration.implements(CircuitClassIds.Screen, session) ||
        declaration.implements(CircuitClassIds.PopResult, session)
    if (!implementsSupportedType) {
      reporter.reportOn(
        annotationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "@CircuitSerializable is only applicable to Screen and PopResult implementations.",
      )
      return
    }

    val typeResolver = MetroFirTypeResolver.Factory(session).create(declaration.symbol)
    val scopeArgument = annotation.classArgument(session, CircuitNames.scope, index = 0)
    val scopeClassId = typeResolver?.let {
      annotation.extractCircuitScopeClassId(session, it, argumentIndex = 0)
    }
    if (scopeClassId == null) {
      reporter.reportOn(
        scopeArgument?.source ?: annotationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "Could not resolve @CircuitSerializable's scope.",
      )
      return
    }

    val registrationClassId = declaration.symbol.classId.circuitSerializerRegistrationClassId()
    val conflicts =
      session.allSessions
        .flatMap { it.findCircuitSerializableClasses() }
        .distinctBy { it.classId }
        .filter { it.classId.circuitSerializerRegistrationClassId() == registrationClassId }
    if (conflicts.size > 1) {
      reporter.reportOn(
        declarationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "@CircuitSerializable declaration ${declaration.symbol.classId.asSingleFqName()} generates " +
          "${registrationClassId.shortClassName}, which conflicts with another " +
          "@CircuitSerializable declaration. Rename one of the declarations.",
      )
      return
    }

    val existingRegistration =
      session.symbolProvider.getClassLikeSymbolByClassId(registrationClassId)
    val existingRegistrationOrigin =
      existingRegistration?.origin?.expectAsOrNull<FirDeclarationOrigin.Plugin>()?.key
    if (
      existingRegistration != null &&
        existingRegistrationOrigin !is CircuitOrigins.SerializerRegistrationClass
    ) {
      reporter.reportOn(
        declarationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "@CircuitSerializable declaration ${declaration.symbol.classId.asSingleFqName()} generates " +
          "${registrationClassId.shortClassName}, which conflicts with an existing declaration. " +
          "Rename one of the declarations.",
      )
      return
    }

    if (session.circuitFirSymbols?.serializerFunction(declaration.symbol) == null) {
      reporter.reportOn(
        annotationSource,
        CIRCUIT_SERIALIZABLE_ERROR,
        "Could not find serializer output for ${declaration.symbol.classId.asSingleFqName()}. " +
          "Apply the kotlinx-serialization compiler plugin.",
      )
    }
  }
}
