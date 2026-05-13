// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.ir.GraphToProcess
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.SyntheticGraphs

internal interface MetroGraphData {
  val contributionData: IrContributionData
  val graphs: List<GraphToProcess>
  val syntheticGraphs: List<GraphToProcess>

  val allGraphs
    get() = graphs + syntheticGraphs
}

@Inject
@SingleIn(IrScope::class)
internal data class MutableMetroGraphData(
  override val contributionData: IrContributionData,
  override val graphs: MutableList<GraphToProcess> = mutableListOf(),
  @SyntheticGraphs override val syntheticGraphs: MutableList<GraphToProcess>,
) : MetroGraphData
