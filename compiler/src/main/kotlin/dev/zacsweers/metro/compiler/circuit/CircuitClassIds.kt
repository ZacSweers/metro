// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.capitalizeUS
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// CircuitClassIds lives in metro-common so the IDE plugin can share it.

internal object CircuitCallableIds {
  private val presenterPackage = FqName("com.slack.circuit.runtime.presenter")
  private val uiPackage = FqName("com.slack.circuit.runtime.ui")

  val presenterOf = CallableId(presenterPackage, Name.identifier("presenterOf"))
  val ui = CallableId(uiPackage, Name.identifier("ui"))
}

internal object CircuitNames {
  val Factory = Name.identifier("CircuitFactory")
  val SubCircuitFactory = Name.identifier("SubCircuitFactory")
  val create = Name.identifier("create")
  val screen = Name.identifier("screen")
  val scope = Name.identifier("scope")
  val navigator = Name.identifier("navigator")
  val context = Name.identifier("context")
  val state = Name.identifier("state")
  val modifier = Name.identifier("modifier")
  val provider = Name.identifier("provider")
  val factoryField = Name.identifier("factory")
}

/** The Circuit runtime family targeted by a generated factory. */
internal enum class CircuitCodegenTarget(
  val injectAnnotation: ClassId,
  val screenClassId: ClassId,
  val uiStateClassId: ClassId,
  val uiClassId: ClassId,
  val uiFactoryClassId: ClassId,
  val presenterClassId: ClassId,
  val presenterFactoryClassId: ClassId,
  val nestedFactoryName: Name,
  private val functionFactorySuffix: String,
) {
  CIRCUIT(
    injectAnnotation = CircuitClassIds.CircuitInject,
    screenClassId = CircuitClassIds.Screen,
    uiStateClassId = CircuitClassIds.CircuitUiState,
    uiClassId = CircuitClassIds.Ui,
    uiFactoryClassId = CircuitClassIds.UiFactory,
    presenterClassId = CircuitClassIds.Presenter,
    presenterFactoryClassId = CircuitClassIds.PresenterFactory,
    nestedFactoryName = CircuitNames.Factory,
    functionFactorySuffix = "Factory",
  ),
  SUBCIRCUIT(
    injectAnnotation = CircuitClassIds.SubCircuitInject,
    screenClassId = CircuitClassIds.SubScreen,
    uiStateClassId = CircuitClassIds.SubCircuitUiState,
    uiClassId = CircuitClassIds.SubUi,
    uiFactoryClassId = CircuitClassIds.SubUiFactory,
    presenterClassId = CircuitClassIds.SubPresenter,
    presenterFactoryClassId = CircuitClassIds.SubPresenterFactory,
    nestedFactoryName = CircuitNames.SubCircuitFactory,
    functionFactorySuffix = "SubCircuitFactory",
  );

  val annotationName: String
    get() = injectAnnotation.shortClassName.asString()

  val uiName: String
    get() = uiClassId.shortClassName.asString()

  val presenterName: String
    get() = presenterClassId.shortClassName.asString()

  fun factoryClassId(factoryType: FactoryType): ClassId =
    when (factoryType) {
      FactoryType.UI -> uiFactoryClassId
      FactoryType.PRESENTER -> presenterFactoryClassId
    }

  fun functionFactoryName(functionName: String): Name =
    Name.identifier("${functionName.capitalizeUS()}$functionFactorySuffix")

  fun functionFactoryType(returnsUnit: Boolean): FactoryType =
    if (this == SUBCIRCUIT || returnsUnit) FactoryType.UI else FactoryType.PRESENTER

  companion object {
    private val byInjectAnnotation = entries.associateBy(CircuitCodegenTarget::injectAnnotation)

    fun forInjectAnnotation(classId: ClassId?): CircuitCodegenTarget? = byInjectAnnotation[classId]
  }
}

/** Type of runtime factory to generate within a [CircuitCodegenTarget]. */
internal enum class FactoryType {
  UI,
  PRESENTER,
}
