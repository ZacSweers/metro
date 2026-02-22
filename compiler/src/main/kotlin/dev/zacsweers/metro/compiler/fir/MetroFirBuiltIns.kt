// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.createDiagnosticReportPath
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

internal class MetroFirBuiltIns(
  session: FirSession,
  val classIds: ClassIds,
  val predicates: ExtensionPredicates,
  val options: MetroOptions,
  val compatContext: CompatContext,
) : FirExtensionSessionComponent(session) {

  val errorFunctionSymbol by memoize {
    session.symbolProvider.getTopLevelFunctionSymbols(kotlinPackageFqn, Symbols.Names.error).first {
      it.valueParameterSymbols.size == 1
    }
  }

  val asContribution by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(Symbols.FqNames.metroRuntimePackage, Symbols.Names.asContribution)
      .first()
  }

  val createGraph by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(Symbols.FqNames.metroRuntimePackage, Symbols.Names.createGraph)
      .first()
  }

  val createGraphFactory by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createGraphFactory,
      )
      .first()
  }

  val createDynamicGraph by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createDynamicGraph,
      )
      .first()
  }

  val createDynamicGraphFactory by memoize {
    session.symbolProvider
      .getTopLevelFunctionSymbols(
        Symbols.FqNames.metroRuntimePackage,
        Symbols.Names.createDynamicGraphFactory,
      )
      .first()
  }

  val createGraphIntrinsicCallableIds by memoize {
    listOf(createGraph, createGraphFactory, createDynamicGraph, createDynamicGraphFactory)
      .associateBy { it.callableId }
  }

  val injectedFunctionClassClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInjectedFunctionClass)
      as FirRegularClassSymbol
  }

  val callableMetadataClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.CallableMetadata)
      as FirRegularClassSymbol
  }

  val graphFactoryInvokeFunctionMarkerClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(
      Symbols.ClassIds.GraphFactoryInvokeFunctionMarkerClass
    ) as FirRegularClassSymbol
  }

  val composableClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Composable)
      as FirRegularClassSymbol
  }

  val stableClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Stable)
      as FirRegularClassSymbol
  }

  val nonRestartableComposable by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.Stable)
      as FirRegularClassSymbol
  }

  val kClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.KClass)
      as FirRegularClassSymbol
  }

  val injectClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroInject)
      as FirRegularClassSymbol
  }

  val assistedClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroAssisted)
      as FirRegularClassSymbol
  }

  val assistedMarkerClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroAssistedMarker)
      as FirRegularClassSymbol
  }

  val providesClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroProvides)
      as FirRegularClassSymbol
  }

  val bindsClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroBinds)
      as FirRegularClassSymbol
  }

  val intoSetClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroIntoSet)
      as FirRegularClassSymbol
  }

  val intoMapClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroIntoMap)
      as FirRegularClassSymbol
  }

  val mapClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(StandardClassIds.Map)
      as FirRegularClassSymbol
  }

  val metroContributionClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroContribution)
      as FirRegularClassSymbol
  }

  val metroImplMarkerClassSymbol by memoize {
    session.symbolProvider.getClassLikeSymbolByClassId(Symbols.ClassIds.metroImplMarker)
      as FirRegularClassSymbol
  }

  /**
   * Binding container hints from
   * [BindingContainerObjectFirGenerator][dev.zacsweers.metro.compiler.fir.generators.BindingContainerObjectFirGenerator].
   * Populated during [getTopLevelClassIds] and read by
   * [ContributionHintFirGenerator][dev.zacsweers.metro.compiler.fir.generators.ContributionHintFirGenerator]
   * to generate contribution hints for generated binding containers.
   *
   * Each entry is a pair of (generatedBindingContainerClassId, scopeClassId).
   */
  val bindingContainerHints = mutableListOf<Pair<ClassId, ClassId>>()

  /**
   * @param diagnosticKey A string identifier for the category of diagnostic being generated. This
   *   will be treated as a prefix path segment. E.g. a key of "keys-populated" will result in
   *   <reports-folder>/keys-populated/<fileName>
   */
  internal inline fun writeDiagnostic(
    diagnosticKey: String,
    fileName: () -> String,
    text: () -> String,
  ) {
    if (session.isCli() && options.reportsEnabled) {
      options.reportsDir.value?.let { writeDiagnostic(it, diagnosticKey, fileName(), text()) }
    }
  }

  private fun writeDiagnostic(
    reportsDir: Path,
    diagnosticKey: String,
    fileName: String,
    text: String,
  ) {
    reportsDir
      .resolve(createDiagnosticReportPath(diagnosticKey, fileName))
      .apply {
        // Ensure that the path leading up to the file has been created
        createParentDirectories()
        deleteIfExists()
      }
      .writeText(text)
  }

  companion object {
    fun getFactory(classIds: ClassIds, options: MetroOptions, compatContext: CompatContext) =
      Factory { session ->
        MetroFirBuiltIns(session, classIds, ExtensionPredicates(classIds), options, compatContext)
      }
  }
}

internal val FirSession.metroFirBuiltIns: MetroFirBuiltIns by FirSession.sessionComponentAccessor()

internal val FirSession.classIds: ClassIds
  get() = metroFirBuiltIns.classIds

internal val FirSession.predicates: ExtensionPredicates
  get() = metroFirBuiltIns.predicates

internal inline val FirSession.compatContext: CompatContext
  get() = metroFirBuiltIns.compatContext
