// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.GraphToProcess
import dev.zacsweers.metro.compiler.ir.IrContributionData

internal interface MetroGraphData {
  val contributionData: IrContributionData
  val graphs: List<GraphToProcess>
  val syntheticGraphs: List<GraphToProcess>

  val allGraphs
    get() = graphs + syntheticGraphs
}

internal data class MutableMetroGraphData(
  override val contributionData: IrContributionData,
  override val graphs: MutableList<GraphToProcess>,
  override val syntheticGraphs: MutableList<GraphToProcess>,
) : MetroGraphData
