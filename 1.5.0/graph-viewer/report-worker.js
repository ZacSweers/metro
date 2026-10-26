/* Copyright (C) 2026 Zac Sweers */
/* SPDX-License-Identifier: Apache-2.0 */
import { GraphReportSession } from './metro-graph-viewer.mjs';

let session;
self.addEventListener('message', event => {
  const request = event.data;
  try {
    if (request.action === 'import') {
      const candidate = new GraphReportSession(JSON.stringify(request.files));
      const summary = JSON.parse(candidate.summaryJson);
      if (!summary.graphs?.length) {
        throw new Error('No graphs were found. Choose a compiler graph report or graphMetadata.json.');
      }
      const selected = summary.graphs.find(graph => graph.name === request.graphName) || summary.graphs[0];
      const dataJson = candidate.renderGraph(selected.name);
      session = candidate;
      self.postMessage({ id: request.id, summary, graphName: selected.name, dataJson });
    } else if (request.action === 'render' && session) {
      self.postMessage({ id: request.id, graphName: request.graphName, dataJson: session.renderGraph(request.graphName) });
    } else {
      throw new Error('Load graph reports before opening a graph.');
    }
  } catch (error) {
    self.postMessage({ id: request.id, error: error?.message || String(error) });
  }
});
