// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
(function () {
  'use strict';

  const PALETTE = ['#009952', '#0078c6', '#f6bc26', '#eb6800', '#d82233', '#a855a1', '#70b5df', '#a6ce39'];
  const INITIAL_LIMIT = 120;
  const LIST_PAGE = 80;
  const COLUMN_GAP = 400;
  const ROW_GAP = 96;
  const ROUTE_GAP = 104;

  function annotationEnd(value, end) {
    if (value[end] !== '(') {
      return end;
    }
    let depth = 0;
    let quote = null;
    let escaped = false;
    do {
      const character = value[end++];
      if (escaped) {
        escaped = false;
      } else if (quote) {
        if (character === '\\') {
          escaped = true;
        } else if (character === quote) {
          quote = null;
        }
      } else if (character === '"' || character === "'") {
        quote = character;
      } else if (character === '(') {
        depth++;
      } else if (character === ')') {
        depth--;
      }
    } while (end < value.length && (depth || quote));
    return end;
  }

  function displayText(value) {
    const source = String(value ?? '');
    const intrinsic = '@dev.zacsweers.metro.internal.MultibindingElement';
    const annotations = /@[\w.]+/g;
    let result = '';
    let cursor = 0;
    let match;
    while ((match = annotations.exec(source))) {
      const end = annotationEnd(source, annotations.lastIndex);
      result += source.slice(cursor, match.index);
      if (match[0] !== intrinsic) {
        result += source.slice(match.index, end);
      }
      cursor = end;
      if (match[0] === intrinsic) {
        while (/\s/.test(source[cursor] || '') && cursor < source.length) {
          cursor++;
        }
      }
      annotations.lastIndex = cursor;
    }
    return result + source.slice(cursor);
  }

  function qualifierCaption(key) {
    const labels = [];
    let remaining = key;
    while (remaining.startsWith('@')) {
      const match = remaining.match(/^@([\w.]+)/);
      if (!match) {
        break;
      }
      const end = annotationEnd(remaining, match[0].length);
      if (!match[1].startsWith('dev.zacsweers.metro.internal.')) {
        labels.push('@' + match[1].split('.').pop() + remaining.slice(match[0].length, end));
      }
      remaining = remaining.slice(end).trimStart();
    }
    return labels.join(' ');
  }

  function indexGraph(data) {
    const byId = new Map();
    for (const original of data.nodes || []) {
      const id = original.id || original.fullKey;
      if (id && !byId.has(id)) {
        byId.set(id, { ...original, id, fullKey: original.fullKey || id });
      }
    }
    const nodes = Array.from(byId.values());
    const outgoing = new Map(nodes.map(node => [node.id, []]));
    const incoming = new Map(nodes.map(node => [node.id, []]));
    const links = [];
    for (const original of data.links || []) {
      if (!byId.has(original.source) || !byId.has(original.target)) {
        continue;
      }
      const edge = { ...original, id: links.length };
      links.push(edge);
      if (!edge.presentationOnly) {
        outgoing.get(edge.source).push(edge);
        incoming.get(edge.target).push(edge);
      }
    }
    const root = nodes.find(node => node.isGraph)?.id || (byId.has(data.graphName) ? data.graphName : null);
    const regions = data.regions?.length ? data.regions : [{ id: root || data.graphName, ownerId: root, graphName: data.graphName, name: byId.get(root)?.name || data.graphName, parentId: null, kind: 'graph', bindingExplanations: data.bindingExplanations || [] }];
    const regionById = new Map(regions.map(region => [region.id, region]));
    for (const node of nodes) {
      node.regionId = node.regionId || regions[0].id;
    }
    const rootMembers = nodes.filter(node => node.isRootMember);
    const bindingNodes = nodes.filter(node => !node.isRootMember && node.kind !== 'Graph');
    const entryPoints = new Set(rootMembers.length ? rootMembers.map(node => node.id) : (outgoing.get(root) || []).map(edge => edge.target));
    const nameCounts = new Map();
    for (const node of nodes) {
      nameCounts.set(node.name, (nameCounts.get(node.name) || 0) + 1);
    }
    for (const node of nodes) {
      node.isEntryPoint = entryPoints.has(node.id);
      if (node.isRootMember) {
        node.caption = node.typeLabel || qualifierCaption(node.requestedKey || node.fullKey);
      } else if (nameCounts.get(node.name) > 1) {
        node.caption = qualifierCaption(node.fullKey) || node.origin || node.declaration || node.pkg;
      }
    }
    const packages = new Map();
    for (const node of bindingNodes) {
      const pkg = node.pkg || '';
      if (!packages.has(pkg)) {
        packages.set(pkg, []);
      }
      packages.get(pkg).push(node);
    }
    const sortedPackages = Array.from(packages.keys()).sort();
    const packageColors = new Map(sortedPackages.map((pkg, index) => [pkg, PALETTE[index % PALETTE.length]]));
    const explanations = new Map();
    for (const region of regions) {
      const localKeys = new Map();
      for (const node of nodes) {
        const keys = new Set(node.regionId === region.id ? [node.fullKey] : []);
        for (const reference of [...(node.inheritedBindings || []), ...(node.includedBindings || [])]) {
          if (reference.regionId === region.id && reference.binding?.key) {
            keys.add(reference.binding.key);
          }
        }
        for (const key of keys) {
          if (!localKeys.has(key)) {
            localKeys.set(key, []);
          }
          localKeys.get(key).push(node.id);
        }
      }
      for (const explanation of region.bindingExplanations || []) {
        const keys = new Set((explanation.candidates || []).map(candidate => candidate.key));
        if (explanation.request?.key) {
          keys.add(explanation.request.key);
        }
        for (const key of keys) {
          for (const id of localKeys.get(key) || []) {
            if (!explanations.has(id)) {
              explanations.set(id, []);
            }
            if (!explanations.get(id).includes(explanation)) {
              explanations.get(id).push(explanation);
            }
          }
        }
      }
    }
    return { nodes, bindingNodes, rootMembers, byId, links, outgoing, incoming, root, entryPoints, packages, packageColors, explanations, regions, regionById, graphInputs: nodes.filter(node => node.isGraphInput) };
  }

  function projectGraph(model, visible, collapsible) {
    const nodes = model.nodes.filter(node => visible.has(node.id));
    const sourceEdges = new Map(model.links.map(edge => [edge.id, edge]));
    const links = [];
    const outgoing = new Map(nodes.map(node => [node.id, []]));
    const incoming = new Map(nodes.map(node => [node.id, []]));
    for (const node of nodes) {
      const seenHidden = new Map();
      const emitted = new Map();
      const queue = (model.outgoing.get(node.id) || []).map(edge => ({ edge, first: edge, deferred: edge.edgeType === 'deferrable' ? edge : null, path: new Set([edge.id]), via: new Set() }));
      for (let index = 0; index < queue.length; index++) {
        const step = queue[index];
        const target = step.edge.target;
        const edgeType = step.deferred ? 'deferrable' : step.first.edgeType;
        const wrapperType = step.deferred?.wrapperType || step.first.wrapperType;
        const key = JSON.stringify([target, edgeType, wrapperType, step.first.hasDefault, step.first.injectorTarget]);
        if (visible.has(target)) {
          let edge = emitted.get(key);
          if (!edge) {
            edge = { ...step.first, target, edgeType, wrapperType, collapsedVia: [], originalEdges: [] };
            emitted.set(key, edge);
            links.push(edge);
            outgoing.get(node.id).push(edge);
            incoming.get(target).push(edge);
          }
          edge.collapsedVia = Array.from(new Set([...edge.collapsedVia, ...step.via]));
          edge.originalEdges = Array.from(new Set([...edge.originalEdges, ...step.path]));
          if (edge.collapsedVia.length) {
            edge.id = 'collapsed:' + JSON.stringify([node.id, key]);
          }
        } else if (collapsible.has(target)) {
          const previous = seenHidden.get(key);
          const via = new Set([...(previous?.via || []), ...step.via, target]);
          const path = new Set([...(previous?.path || []), ...step.path]);
          if (previous && via.size === previous.via.size && path.size === previous.path.size) {
            continue;
          }
          seenHidden.set(key, { via, path });
          for (const edge of model.outgoing.get(target) || []) {
            queue.push({ edge, first: step.first, deferred: step.deferred || (edge.edgeType === 'deferrable' ? edge : null), path: new Set([...path, edge.id]), via });
          }
        }
      }
    }
    const entryPoints = new Set((outgoing.get(model.root) || []).map(edge => edge.target));
    for (const member of model.rootMembers) {
      if (visible.has(member.id)) {
        entryPoints.add(member.id);
      }
    }
    for (const edge of links) {
      edge.includes = edge.edgeType === 'includes' || edge.originalEdges.some(id => sourceEdges.get(id)?.edgeType === 'includes');
    }
    return { ...model, nodes, links, outgoing, incoming, entryPoints };
  }

  function rootConnections(model, target) {
    const reachable = new Set();
    const queue = model.root ? [model.root] : [];
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      if (reachable.has(id)) {
        continue;
      }
      reachable.add(id);
      for (const edge of model.outgoing.get(id) || []) {
        queue.push(edge.target);
      }
    }
    if (target === model.root) {
      return new Set(model.links.filter(edge => !edge.presentationOnly && reachable.has(edge.source)).map(edge => edge.id));
    }
    const ancestors = new Set([target]);
    const upstream = [target];
    for (let index = 0; index < upstream.length; index++) {
      const id = upstream[index];
      if (id === model.root) {
        continue;
      }
      for (const edge of model.incoming.get(id) || []) {
        if (!ancestors.has(edge.source)) {
          ancestors.add(edge.source);
          upstream.push(edge.source);
        }
      }
    }
    return new Set(model.links.filter(edge => !edge.presentationOnly && reachable.has(edge.source) && ancestors.has(edge.source) && ancestors.has(edge.target)).map(edge => edge.id));
  }

  function rootRoute(model, target) {
    if (!model.root || !model.byId.has(target)) {
      return null;
    }
    const queue = [model.root];
    const previous = new Map([[model.root, null]]);
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      if (id === target) {
        const nodes = [];
        const edges = [];
        let cursor = target;
        while (cursor !== null) {
          nodes.push(cursor);
          const step = previous.get(cursor);
          if (!step) {
            break;
          }
          edges.push(step.edge);
          cursor = step.from;
        }
        return { nodes: nodes.reverse(), edges: edges.reverse() };
      }
      for (const edge of model.outgoing.get(id) || []) {
        if (!previous.has(edge.target)) {
          previous.set(edge.target, { from: id, edge });
          queue.push(edge.target);
        }
      }
    }
    return null;
  }

  function neighborhood(model, start, direction, depth) {
    const starts = Array.isArray(start) ? start : [start];
    const visited = new Set(starts);
    let frontier = starts;
    for (let hop = 0; hop < depth; hop++) {
      const next = [];
      for (const id of frontier) {
        const edges = [];
        if (direction !== 'consumers') {
          edges.push(...(model.outgoing.get(id) || []));
        }
        if (direction !== 'dependencies') {
          edges.push(...(model.incoming.get(id) || []));
        }
        for (const edge of edges) {
          const neighbor = edge.source === id ? edge.target : edge.source;
          if (!visited.has(neighbor)) {
            visited.add(neighbor);
            next.push(neighbor);
          }
        }
      }
      frontier = next;
    }
    return Array.from(visited);
  }

  function layoutBindings(model) {
    const ranks = new Map();
    const queue = [];
    if (model.root) {
      ranks.set(model.root, 0);
      queue.push(model.root);
    }
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      for (const edge of model.outgoing.get(id) || []) {
        if (!ranks.has(edge.target)) {
          ranks.set(edge.target, Math.min(24, ranks.get(id) + (edge.rootMembership ? 0 : 1)));
          queue.push(edge.target);
        }
      }
    }
    const columns = new Map();
    for (const node of model.nodes) {
      const rank = ranks.get(node.id) ?? 1;
      if (!columns.has(rank)) {
        columns.set(rank, []);
      }
      columns.get(rank).push(node);
    }
    for (const column of columns.values()) {
      column.sort((a, b) => (a.pkg || '').localeCompare(b.pkg || '') || a.fullKey.localeCompare(b.fullKey));
    }
    const positions = new Map();
    const rankOrder = Array.from(columns.keys()).sort((a, b) => a - b);
    const rowLimit = Math.max(12, Math.ceil(Math.sqrt(model.nodes.length * COLUMN_GAP / (ROW_GAP * 1.6))));
    const assign = () => {
      let nextColumn = 0;
      for (const rank of rankOrder) {
        const column = columns.get(rank);
        const count = Math.ceil(column.length / rowLimit);
        const rows = Math.ceil(column.length / count);
        column.forEach((node, index) => {
          const lane = Math.floor(index / rows);
          const laneSize = Math.min(rows, column.length - lane * rows);
          positions.set(node.id, { x: (nextColumn + lane) * COLUMN_GAP, y: (index % rows - (laneSize - 1) / 2) * ROW_GAP });
        });
        nextColumn += count;
      }
    };
    assign();
    // Reorder each column around connected stations once, then keep those positions for browsing.
    for (let pass = 0; pass < 4; pass++) {
      const order = pass % 2 ? [...rankOrder].reverse() : rankOrder;
      for (const rank of order) {
        const column = columns.get(rank);
        const score = new Map();
        for (const node of column) {
          const edges = pass % 2 ? model.outgoing.get(node.id) : model.incoming.get(node.id);
          const neighbors = edges.map(edge => positions.get(edge.source === node.id ? edge.target : edge.source)?.y).filter(value => value !== undefined);
          score.set(node.id, neighbors.length ? neighbors.reduce((sum, value) => sum + value, 0) / neighbors.length : positions.get(node.id).y);
        }
        column.sort((a, b) => score.get(a.id) - score.get(b.id) || a.fullKey.localeCompare(b.fullKey));
        assign();
      }
    }
    positionRootMembers(model, positions);
    positionGraphInputs(model, positions);
    return flowPositions(positions);
  }

  function flowPositions(points) {
    return new Map(Array.from(points, ([id, point]) => [id, { x: -point.x, y: point.y }]));
  }

  function positionRoundBoundary(model, points, radius) {
    for (const [members, left] of [[model.graphInputs, true], [model.rootMembers, false]]) {
      members.forEach((node, index) => {
        const spread = members.length === 1 ? 0 : (index / (members.length - 1) - 0.5) * Math.PI * 0.75;
        const angle = (left ? Math.PI : 0) + spread;
        points.set(node.id, { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius });
      });
    }
  }

  function positionRootMembers(model, points) {
    const root = points.get(model.root);
    const members = model.rootMembers.filter(node => points.has(node.id));
    if (!root || !members.length) {
      return;
    }
    members.sort((a, b) => points.get(a.id).y - points.get(b.id).y || a.id.localeCompare(b.id));
    const bindings = model.nodes.filter(node => !node.isGraph && !node.isRootMember && !node.isGraphInput).map(node => points.get(node.id)).filter(Boolean);
    const x = bindings.length ? Math.min(root.x, Math.min(...bindings.map(point => point.x)) - COLUMN_GAP) : root.x;
    const center = members.reduce((sum, node) => sum + points.get(node.id).y, 0) / members.length;
    const start = center - (members.length - 1) * ROW_GAP / 2;
    members.forEach((node, index) => points.set(node.id, { x, y: start + index * ROW_GAP }));
    points.set(model.root, { x: x - 280, y: start - 64 });
  }

  function positionGraphInputs(model, points) {
    const root = points.get(model.root);
    const members = model.rootMembers.map(node => points.get(node.id)).filter(Boolean);
    const bindings = model.nodes.filter(node => !node.isGraph && !node.isRootMember && !node.isGraphInput).map(node => points.get(node.id)).filter(Boolean);
    const x = bindings.length ? Math.max(...bindings.map(point => point.x)) + COLUMN_GAP : null;
    const bottom = members.length ? Math.max(...members.map(point => point.y)) : root?.y;
    model.graphInputs.forEach((node, index) => {
      const point = points.get(node.id);
      if (point) {
        const y = root ? bottom + (index + 1) * ROW_GAP : point.y;
        points.set(node.id, { x: x ?? point.x, y });
      }
    });
  }

  function layoutRadial(model) {
    const ranks = new Map(model.root ? [[model.root, 0]] : []);
    const queue = Array.from(ranks.keys());
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      for (const edge of model.outgoing.get(id) || []) {
        if (!ranks.has(edge.target)) {
          ranks.set(edge.target, ranks.get(id) + 1);
          queue.push(edge.target);
        }
      }
    }
    const outer = Math.max(0, ...ranks.values()) + 1;
    const rings = new Map();
    const points = new Map(model.root ? [[model.root, { x: 0, y: 0 }]] : []);
    for (const node of model.nodes) {
      if (node.id === model.root || node.isRootMember || node.isGraphInput) {
        continue;
      }
      const rank = ranks.get(node.id) || outer;
      if (!rings.has(rank)) {
        rings.set(rank, []);
      }
      rings.get(rank).push(node);
    }
    let radius = 160;
    for (const [rank, ring] of Array.from(rings).sort((a, b) => b[0] - a[0])) {
      radius = Math.max(radius + 240, ring.length * 70 / (2 * Math.PI));
      ring.sort((a, b) => (a.pkg || '').localeCompare(b.pkg || '') || a.fullKey.localeCompare(b.fullKey));
      ring.forEach((node, index) => {
        const angle = index * 2 * Math.PI / ring.length - Math.PI / 2 + (rank % 2) * 0.12;
        points.set(node.id, { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius });
      });
    }
    const boundary = model.nodes.filter(node => node.isRootMember || node.isGraphInput);
    const boundaryRadius = Math.max(radius + 240, boundary.length * 70 / (2 * Math.PI));
    positionRoundBoundary(model, points, boundaryRadius);
    return points;
  }

  function layoutCircular(model) {
    const nodes = model.nodes.filter(node => node.id !== model.root);
    const radius = Math.max(240, nodes.length * 80 / (2 * Math.PI));
    const points = new Map(model.root ? [[model.root, { x: 0, y: 0 }]] : []);
    nodes.forEach((node, index) => {
      const angle = index * 2 * Math.PI / nodes.length - Math.PI / 2;
      points.set(node.id, { x: Math.cos(angle) * radius, y: Math.sin(angle) * radius });
    });
    return points;
  }

  function groupPackages(model, maximum = 12) {
    const root = { prefix: '', children: new Map(), packages: [], count: 0 };
    for (const [pkg, nodes] of model.packages) {
      let branch = root;
      const parts = pkg ? pkg.split('.') : [''];
      for (let index = 0; index < parts.length; index++) {
        const part = parts[index];
        if (!branch.children.has(part)) {
          branch.children.set(part, { prefix: parts.slice(0, index + 1).join('.'), children: new Map(), packages: [], count: 0 });
        }
        branch = branch.children.get(part);
        branch.packages.push(pkg);
        branch.count += nodes.length;
      }
    }
    const compact = branch => {
      while (branch.children.size === 1) {
        const child = branch.children.values().next().value;
        if (child.count !== branch.count) {
          break;
        }
        branch = child;
      }
      return branch;
    };
    const groups = Array.from(root.children.values(), compact);
    const namespaceLimit = Math.max(2, Math.floor(maximum / 2));
    if (groups.length > namespaceLimit) {
      groups.sort((a, b) => b.count - a.count || a.prefix.localeCompare(b.prefix));
      const remaining = groups.splice(namespaceLimit - 1);
      groups.push({ prefix: 'Other namespaces', children: new Map(), packages: remaining.flatMap(group => group.packages), count: remaining.reduce((sum, group) => sum + group.count, 0) });
    }
    const split = group => {
      const pieces = Array.from(group.children.values(), compact);
      const childPackages = new Set(pieces.flatMap(child => child.packages));
      const ownPackages = group.packages.filter(pkg => !childPackages.has(pkg));
      if (ownPackages.length) {
        pieces.push({ prefix: group.prefix, children: new Map(), packages: ownPackages, count: ownPackages.reduce((sum, pkg) => sum + model.packages.get(pkg).length, 0) });
      }
      return pieces;
    };
    while (groups.length < maximum) {
      const candidates = groups.filter(group => group.children.size > 0 && split(group).length > 1);
      candidates.sort((a, b) => b.count - a.count || a.prefix.localeCompare(b.prefix));
      const largest = candidates[0];
      if (!largest) {
        break;
      }
      const pieces = split(largest).sort((a, b) => b.count - a.count || a.prefix.localeCompare(b.prefix));
      const first = pieces.shift();
      const basePrefix = largest.basePrefix || largest.prefix;
      const remainder = pieces.length === 1 ? pieces[0] : {
        prefix: basePrefix + ' · other', basePrefix,
        children: new Map(pieces.map(piece => [piece.prefix, piece])),
        packages: pieces.flatMap(piece => piece.packages),
        count: pieces.reduce((sum, piece) => sum + piece.count, 0),
      };
      groups.splice(groups.indexOf(largest), 1, first, remainder);
    }
    groups.sort((a, b) => b.count - a.count || a.prefix.localeCompare(b.prefix));
    const columns = Math.max(2, Math.ceil(Math.sqrt(groups.length * 0.75)));
    return groups.map((group, index) => ({
      id: 'package:' + group.prefix, pkg: group.prefix, packages: group.packages,
      x: (index % columns) * 420, y: Math.floor(index / columns) * 180,
      color: PALETTE[index % PALETTE.length],
    }));
  }

  function layoutRegions(model, kind) {
    const suppliedRegions = model.regions instanceof Map ? Array.from(model.regions.values()) : model.regions || [];
    const regions = new Map(suppliedRegions.filter(region => region.id).map(region => [region.id, region]));
    if (!regions.size) {
      const id = model.root || 'graph:root';
      regions.set(id, {
        id, ownerId: id, parentId: null, nodeIds: model.nodes.map(node => node.id),
        rootIds: (model.rootMembers || []).map(node => node.id),
        inputIds: (model.graphInputs || []).map(node => node.id),
      });
    }
    const parents = new Map(Array.from(regions, ([id, region]) => [id, regions.has(region.parentId) && region.parentId !== id ? region.parentId : null]));
    const checked = new Set();
    for (const id of regions.keys()) {
      const path = new Set();
      let current = id;
      while (current && !checked.has(current)) {
        if (path.has(current)) {
          parents.set(current, null);
          break;
        }
        path.add(current);
        current = parents.get(current);
      }
      for (const visited of path) {
        checked.add(visited);
      }
    }
    const children = new Map(Array.from(regions.keys(), id => [id, []]));
    const roots = [];
    for (const [id, parentId] of parents) {
      if (parentId) {
        children.get(parentId).push(id);
      } else {
        roots.push(id);
      }
    }
    const fallbackOwners = new Map();
    for (const [id, region] of regions) {
      for (const nodeId of [region.ownerId || id, ...(region.nodeIds || []), ...(region.rootIds || []), ...(region.inputIds || [])]) {
        if (!fallbackOwners.has(nodeId)) {
          fallbackOwners.set(nodeId, id);
        }
      }
    }
    const nodesByRegion = new Map(Array.from(regions.keys(), id => [id, []]));
    const nodeOwners = new Map();
    for (const node of model.nodes) {
      const regionId = regions.has(node.regionId) ? node.regionId : fallbackOwners.get(node.id);
      if (nodesByRegion.has(regionId)) {
        nodesByRegion.get(regionId).push(node);
        nodeOwners.set(node.id, regionId);
      }
    }
    const linksByRegion = new Map(Array.from(regions.keys(), id => [id, []]));
    for (const edge of model.links) {
      const regionId = nodeOwners.get(edge.source);
      if (regionId && regionId === nodeOwners.get(edge.target)) {
        linksByRegion.get(regionId).push(edge);
      }
    }
    const order = [...roots];
    for (let index = 0; index < order.length; index++) {
      order.push(...children.get(order[index]));
    }
    const localLayouts = new Map();
    const layoutKind = kind === 'radial' || kind === 'circular' ? kind : 'full';
    const layout = layoutKind === 'radial' ? layoutRadial : layoutKind === 'circular' ? layoutCircular : layoutBindings;
    for (const id of order) {
      const region = regions.get(id);
      const ownerId = region.ownerId || id;
      const rootIds = new Set(region.rootIds || []);
      const inputIds = new Set(region.inputIds || []);
      const nodes = nodesByRegion.get(id).map(node => ({
        ...node, regionId: id, fullKey: node.fullKey || node.id, isGraph: node.id === ownerId,
        isRootMember: rootIds.has(node.id), isGraphInput: inputIds.has(node.id),
      }));
      if (!nodes.some(node => node.id === ownerId)) {
        nodes.push({ id: ownerId, fullKey: region.graphName || ownerId, name: region.name || ownerId, isGraph: true, kind: 'Graph', regionId: id });
      }
      const byId = new Map(nodes.map(node => [node.id, node]));
      const outgoing = new Map(nodes.map(node => [node.id, []]));
      const incoming = new Map(nodes.map(node => [node.id, []]));
      const links = linksByRegion.get(id);
      for (const edge of links) {
        if (!edge.presentationOnly) {
          outgoing.get(edge.source).push(edge);
          incoming.get(edge.target).push(edge);
        }
      }
      const rootMembers = nodes.filter(node => node.isRootMember);
      const graphInputs = nodes.filter(node => node.isGraphInput);
      const submodel = { ...model, nodes, byId, links, outgoing, incoming, root: ownerId, rootMembers, graphInputs, entryPoints: rootIds };
      const points = layout(submodel);
      const stations = nodes.map(node => ({ ...node, ...points.get(node.id) }));
      const ownFrame = regionBoundary(region, stations, [], { kind: layoutKind, nodes: stations, edges: links }, { x: 1, y: 0 });
      const ownWidth = ownFrame.right - ownFrame.left;
      const ownHeight = ownFrame.bottom - ownFrame.top;
      const center = { x: (ownFrame.left + ownFrame.right) / 2, y: (ownFrame.top + ownFrame.bottom) / 2 };
      localLayouts.set(id, {
        nodes: stations.map(node => ({ ...node, x: node.x - center.x, y: node.y - center.y })),
        radius: Math.hypot(ownWidth, ownHeight) / 2,
      });
    }
    const centers = new Map();
    const gap = 480;
    const placeChildren = (id, angle, span) => {
      const siblings = children.get(id);
      const parent = centers.get(id);
      const parentRadius = localLayouts.get(id).radius;
      const largest = Math.max(0, ...siblings.map(child => localLayouts.get(child).radius));
      const sector = span / Math.max(1, siblings.length);
      const clearance = siblings.length > 1 ? 1.5 * (largest + gap) / Math.sin(Math.min(Math.PI / 2, sector / 2)) : 0;
      siblings.forEach((childId, index) => {
        const direction = angle - span / 2 + sector * (index + 0.5);
        const distance = Math.max(parentRadius + localLayouts.get(childId).radius + gap, clearance);
        centers.set(childId, { x: parent.x + Math.cos(direction) * distance, y: parent.y + Math.sin(direction) * distance });
        placeChildren(childId, direction, Math.min(sector, Math.PI / 2));
      });
    };
    const placedNodes = () => Array.from(centers, ([id, center]) => localLayouts.get(id).nodes.map(node => ({ ...node, x: node.x + center.x, y: node.y + center.y }))).flat();
    const safeRegions = Array.from(regions.values(), region => ({ ...region, parentId: parents.get(region.id) }));
    const placedFrames = () => visibilityBoundaries(safeRegions, { kind: layoutKind, nodes: placedNodes(), edges: model.links }, { x: 1, y: 0 });
    const primary = roots.find(id => regions.get(id).kind !== 'dependency') || roots[0];
    centers.set(primary, { x: 0, y: 0 });
    placeChildren(primary, -Math.PI / 4, Math.PI * 2);
    for (const id of roots.filter(root => root !== primary)) {
      const normal = { x: Math.SQRT1_2, y: -Math.SQRT1_2 };
      const support = Math.max(...placedFrames().flatMap(frame => frame.contour.map(point => point.x * normal.x + point.y * normal.y)));
      const previous = new Set(centers.keys());
      centers.set(id, { x: 0, y: 0 });
      placeChildren(id, -Math.PI / 4, Math.PI);
      const family = new Set(Array.from(centers.keys()).filter(regionId => !previous.has(regionId)));
      const extent = Math.min(...placedFrames().filter(frame => family.has(frame.id)).flatMap(frame => frame.contour.map(point => point.x * normal.x + point.y * normal.y)));
      const distance = support + gap - extent;
      for (const regionId of family) {
        const point = centers.get(regionId);
        centers.set(regionId, { x: point.x + normal.x * distance, y: point.y + normal.y * distance });
      }
    }
    return {
      positions: new Map(placedNodes().map(node => [node.id, { x: node.x, y: node.y }])),
      boundaries: new Map(placedFrames().map(frame => [frame.id, frame])),
    };
  }

  function boundaryContour(nodes, boundary, childContours = []) {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    let minSum = Infinity, maxSum = -Infinity, minDifference = Infinity, maxDifference = -Infinity;
    const include = (x, y) => {
      if (!Number.isFinite(x) || !Number.isFinite(y)) {
        return;
      }
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
      minSum = Math.min(minSum, x + y);
      maxSum = Math.max(maxSum, x + y);
      minDifference = Math.min(minDifference, x - y);
      maxDifference = Math.max(maxDifference, x - y);
    };
    const envelope = (left, top, right, bottom, padding) => {
      include(left - padding, top);
      include(left, top - padding);
      include(right, top - padding);
      include(right + padding, top);
      include(right + padding, bottom);
      include(right, bottom + padding);
      include(left, bottom + padding);
      include(left - padding, bottom);
    };
    const rawFlowX = Number.isFinite(boundary?.flowX) ? boundary.flowX : 0;
    const rawFlowY = Number.isFinite(boundary?.flowY) ? boundary.flowY : 0;
    const flowLength = Math.hypot(rawFlowX, rawFlowY);
    const flowX = flowLength > 0 ? rawFlowX / flowLength : 1;
    const flowY = flowLength > 0 ? rawFlowY / flowLength : 0;
    for (const node of nodes) {
      if (node.isGraph || node.kind === 'Graph' || !Number.isFinite(node.x) || !Number.isFinite(node.y)) {
        continue;
      }
      const radius = Number.isFinite(node.radius) ? Math.max(0, node.radius) : 6;
      const isBoundaryNode = node.isGraphInput || node.isRootMember;
      if (isBoundaryNode) {
        const inward = node.isGraphInput ? 1 : -1;
        const padding = radius + 18;
        include(node.x, node.y);
        include(node.x + (flowX * inward - flowY) * padding, node.y + (flowY * inward + flowX) * padding);
        include(node.x + (flowX * inward + flowY) * padding, node.y + (flowY * inward - flowX) * padding);
      } else {
        envelope(node.x - radius, node.y - radius, node.x + radius, node.y + radius, 18);
      }
      const name = String(node.name || node.fullKey || node.id || '');
      const caption = String(node.caption || '');
      const hasSubtitle = node.isRootMember || node.isGraphInput || node.isGraphInstance || node.isIncludedGraph || node.isEntryPoint;
      const measuredWidth = Number.isFinite(node.labelWidth) ? Math.max(0, node.labelWidth) : null;
      const labelWidth = measuredWidth ?? Math.min(270, Math.max(name.length * 7.2, caption.length * 6, hasSubtitle ? 84 : 0));
      if (labelWidth > 0) {
        const lines = Number(Boolean(name)) + Number(Boolean(caption)) + Number(Boolean(hasSubtitle));
        const labelHeight = Number.isFinite(node.labelHeight) ? Math.max(0, node.labelHeight) : Math.max(1, lines) * 16 + 4;
        const sideIsLeft = node.labelSide ? node.labelSide === 'left' : Boolean(node.isRootMember);
        const leftLabel = !node.labelBelow && sideIsLeft;
        const left = leftLabel ? node.x - labelWidth - 19 : node.x + radius + 5;
        const top = node.y + (node.labelBelow ? 9 : -13);
        envelope(left, top, left + labelWidth + 10, top + labelHeight, isBoundaryNode ? 4 : 10);
      }
    }
    for (const contour of childContours) {
      for (const point of contour) {
        include(point.x, point.y);
      }
    }
    const fallback = () => {
      const hasContents = Number.isFinite(minX);
      let left = hasContents ? minX - 24 : Number.isFinite(boundary?.left) ? boundary.left : -80;
      let right = hasContents ? maxX + 24 : Number.isFinite(boundary?.right) ? boundary.right : 80;
      let top = hasContents ? minY - 24 : Number.isFinite(boundary?.top) ? boundary.top : -48;
      let bottom = hasContents ? maxY + 24 : Number.isFinite(boundary?.bottom) ? boundary.bottom : 48;
      if (right < left) {
        [left, right] = [right, left];
      }
      if (bottom < top) {
        [top, bottom] = [bottom, top];
      }
      if (right - left < 48) {
        left -= 24;
        right += 24;
      }
      if (bottom - top < 48) {
        top -= 24;
        bottom += 24;
      }
      const corner = Math.min(24, (right - left) / 4, (bottom - top) / 4);
      return [
        { x: left + corner, y: top }, { x: right - corner, y: top },
        { x: right, y: top + corner }, { x: right, y: bottom - corner },
        { x: right - corner, y: bottom }, { x: left + corner, y: bottom },
        { x: left, y: bottom - corner }, { x: left, y: top + corner },
      ];
    };
    if (!Number.isFinite(minX)) {
      return fallback();
    }
    const magnitude = Math.max(1, Math.abs(minX), Math.abs(maxX), Math.abs(minY), Math.abs(maxY));
    const epsilon = Math.max(1e-7, magnitude * Number.EPSILON * 16);
    let polygon = [{ x: minX, y: minY }, { x: maxX, y: minY }, { x: maxX, y: maxY }, { x: minX, y: maxY }];
    const planes = [[1, 1, maxSum], [-1, -1, -minSum], [1, -1, maxDifference], [-1, 1, -minDifference]];
    for (const [normalX, normalY, limit] of planes) {
      const clipped = [];
      for (let index = 0; index < polygon.length; index++) {
        const a = polygon[index];
        const b = polygon[(index + 1) % polygon.length];
        const aDistance = normalX * a.x + normalY * a.y - limit;
        const bDistance = normalX * b.x + normalY * b.y - limit;
        const aInside = aDistance <= epsilon;
        const bInside = bDistance <= epsilon;
        if (aInside) {
          clipped.push(a);
        }
        if (aInside !== bInside) {
          const amount = Math.max(0, Math.min(1, aDistance / (aDistance - bDistance)));
          clipped.push({ x: a.x + (b.x - a.x) * amount, y: a.y + (b.y - a.y) * amount });
        }
      }
      polygon = clipped;
    }
    polygon = polygon.filter((point, index) => {
      const previous = polygon[(index + polygon.length - 1) % polygon.length];
      return Math.hypot(point.x - previous.x, point.y - previous.y) > epsilon;
    });
    polygon = polygon.filter((point, index) => {
      const previous = polygon[(index + polygon.length - 1) % polygon.length];
      const next = polygon[(index + 1) % polygon.length];
      const cross = (point.x - previous.x) * (next.y - point.y) - (point.y - previous.y) * (next.x - point.x);
      const length = Math.hypot(point.x - previous.x, point.y - previous.y) + Math.hypot(next.x - point.x, next.y - point.y);
      return Math.abs(cross) > epsilon * length;
    });
    return polygon.length >= 3 ? polygon : fallback();
  }

  function offsetContour(contour, distance) {
    if (!Array.isArray(contour) || contour.length < 3 || !Number.isFinite(distance)) {
      return [];
    }
    if (contour.some(point => !Number.isFinite(point?.x) || !Number.isFinite(point?.y))) {
      return [];
    }
    const origin = contour[0];
    let magnitude = Math.max(1, Math.abs(distance));
    for (const point of contour) {
      magnitude = Math.max(magnitude, Math.abs(point.x), Math.abs(point.y));
    }
    const epsilon = Math.max(1e-8, magnitude * Number.EPSILON * 32);
    const clean = points => {
      let result = points.filter((point, index) => {
        const previous = points[(index + points.length - 1) % points.length];
        return Math.hypot(point.x - previous.x, point.y - previous.y) > epsilon;
      });
      result = result.filter((point, index) => {
        const previous = result[(index + result.length - 1) % result.length];
        const next = result[(index + 1) % result.length];
        const cross = (point.x - previous.x) * (next.y - point.y) - (point.y - previous.y) * (next.x - point.x);
        const length = Math.hypot(point.x - previous.x, point.y - previous.y) + Math.hypot(next.x - point.x, next.y - point.y);
        return Math.abs(cross) > epsilon * length;
      });
      return result;
    };
    const area = points => points.reduce((sum, point, index) => {
      const next = points[(index + 1) % points.length];
      return sum + point.x * next.y - point.y * next.x;
    }, 0);
    let polygon = clean(contour.map(point => ({ x: point.x - origin.x, y: point.y - origin.y })));
    if (polygon.length < 3) {
      return [];
    }
    if (area(polygon) < 0) {
      polygon.reverse();
    }
    const planes = [];
    let perimeter = 0;
    for (let index = 0; index < polygon.length; index++) {
      const point = polygon[index];
      const next = polygon[(index + 1) % polygon.length];
      const previous = polygon[(index + polygon.length - 1) % polygon.length];
      const dx = next.x - point.x;
      const dy = next.y - point.y;
      const length = Math.hypot(dx, dy);
      const turn = (point.x - previous.x) * dy - (point.y - previous.y) * dx;
      if (turn < -epsilon * length) {
        return [];
      }
      const x = dy / length;
      const y = -dx / length;
      planes.push({ x, y, limit: x * point.x + y * point.y + distance });
      perimeter += length;
    }
    if (area(polygon) <= epsilon * perimeter) {
      return [];
    }
    if (distance > 0) {
      polygon = polygon.map((point, index) => {
        const previous = planes[(index + planes.length - 1) % planes.length];
        const current = planes[index];
        const divisor = 1 + previous.x * current.x + previous.y * current.y;
        if (divisor <= Number.EPSILON * 16) {
          return { x: NaN, y: NaN };
        }
        const amount = distance / divisor;
        return { x: point.x + (previous.x + current.x) * amount, y: point.y + (previous.y + current.y) * amount };
      });
    } else if (distance < 0) {
      for (const plane of planes) {
        const clipped = [];
        for (let index = 0; index < polygon.length; index++) {
          const point = polygon[index];
          const next = polygon[(index + 1) % polygon.length];
          const pointDistance = plane.x * point.x + plane.y * point.y - plane.limit;
          const nextDistance = plane.x * next.x + plane.y * next.y - plane.limit;
          const pointInside = pointDistance <= epsilon;
          const nextInside = nextDistance <= epsilon;
          if (pointInside) {
            clipped.push(point);
          }
          if (pointInside !== nextInside) {
            const amount = Math.max(0, Math.min(1, pointDistance / (pointDistance - nextDistance)));
            clipped.push({ x: point.x + (next.x - point.x) * amount, y: point.y + (next.y - point.y) * amount });
          }
        }
        polygon = clipped;
        if (polygon.length < 3) {
          return [];
        }
      }
    }
    if (polygon.some(point => !Number.isFinite(point.x) || !Number.isFinite(point.y))) {
      return [];
    }
    polygon = clean(polygon);
    if (polygon.length < 3 || area(polygon) <= epsilon * perimeter) {
      return [];
    }
    const result = polygon.map(point => ({ x: point.x + origin.x, y: point.y + origin.y }));
    return result.every(point => Number.isFinite(point.x) && Number.isFinite(point.y)) ? result : [];
  }

  function boundaryIntersection(contour, point, vector) {
    const validPoint = Number.isFinite(point?.x) && Number.isFinite(point?.y);
    const validVector = Number.isFinite(vector?.x) && Number.isFinite(vector?.y);
    if (!validPoint || !validVector) {
      return null;
    }
    const vectorLength = Math.hypot(vector.x, vector.y);
    if (!Number.isFinite(vectorLength) || vectorLength === 0) {
      return null;
    }
    const polygon = offsetContour(contour, 0);
    if (polygon.length < 3) {
      return null;
    }
    const dx = vector.x / vectorLength;
    const dy = vector.y / vectorLength;
    const magnitude = Math.max(1, Math.abs(point.x), Math.abs(point.y));
    const epsilon = Math.max(1e-8, magnitude * Number.EPSILON * 32);
    let distance = Infinity;
    for (let index = 0; index < polygon.length; index++) {
      const start = polygon[index];
      const end = polygon[(index + 1) % polygon.length];
      const length = Math.hypot(end.x - start.x, end.y - start.y);
      const normalX = (end.y - start.y) / length;
      const normalY = (start.x - end.x) / length;
      const clearance = normalX * (start.x - point.x) + normalY * (start.y - point.y);
      if (clearance < -epsilon) {
        return null;
      }
      const outward = normalX * dx + normalY * dy;
      if (outward > 1e-12) {
        distance = Math.min(distance, Math.max(0, clearance / outward));
      }
    }
    if (!Number.isFinite(distance)) {
      return null;
    }
    return { x: point.x + dx * distance, y: point.y + dy * distance };
  }

  function graphBoundary(nodes, kind) {
    if (!nodes.length) {
      return null;
    }
    if (kind === 'radial' || kind === 'circular') {
      const boundaryNodes = nodes.filter(node => node.isRootMember || node.isGraphInput);
      const radius = Math.max(...nodes.map(node => Math.hypot(node.x, node.y))) + (boundaryNodes.length ? 0 : 120);
      return { left: -radius, right: radius, top: -radius, bottom: radius, corner: radius, labelX: -120, labelY: -radius - 32, opacity: 1 };
    }
    const minX = Math.min(...nodes.map(node => node.x));
    const maxX = Math.max(...nodes.map(node => node.x));
    const minY = Math.min(...nodes.map(node => node.y));
    const maxY = Math.max(...nodes.map(node => node.y));
    const entries = nodes.filter(node => node.isRootMember);
    const inputs = nodes.filter(node => node.isGraphInput);
    if (kind === 'route') {
      const inputAtStart = nodes[0].isGraphInput;
      const entryAtEnd = nodes[nodes.length - 1].isRootMember;
      const top = minY - (inputAtStart ? 0 : 80);
      const bottom = Math.max(maxY + (entryAtEnd ? 0 : 88), top + 160);
      return { left: minX - 36, right: maxX + 360, top, bottom, corner: 20, labelX: minX + 24, labelY: top - 48, opacity: 1 };
    }
    const interior = nodes.filter(node => !node.isRootMember && !node.isGraphInput);
    const interiorLeft = interior.length ? Math.min(...interior.map(node => node.x)) - 64 : minX;
    const interiorRight = interior.length ? Math.max(...interior.map(node => node.x)) + 320 : maxX;
    const left = inputs.length ? Math.min(interiorLeft, ...inputs.map(node => node.x)) : minX - 64;
    const right = entries.length ? Math.max(interiorRight, ...entries.map(node => node.x)) : maxX + 340;
    const labelY = (inputs.length ? Math.min(...inputs.map(node => node.y)) : minY) - 64;
    return { left, right: Math.max(left + 360, right), top: minY - 104, bottom: maxY + 88, corner: 32, labelX: left + 18, labelY, opacity: 1 };
  }

  function projectBoundaryNode(node, boundary, regions, point, vector) {
    // Keep roots and inputs outside outlines that don't include their bindings.
    const excluded = regions.filter(region => !region.members.has(node.id) && region.opacity > 0.001);
    const inside = (candidate, contour) => contour.every((start, index) => {
      const end = contour[(index + 1) % contour.length];
      const cross = (end.x - start.x) * (candidate.y - start.y) - (end.y - start.y) * (candidate.x - start.x);
      return cross >= -1e-6;
    });
    const allowed = candidate => !excluded.some(region => inside(candidate, region.contour));
    const preferred = boundaryIntersection(boundary.contour, point, vector);
    if (preferred && allowed(preferred)) {
      return preferred;
    }
    let nearest = null;
    let nearestDistance = Infinity;
    for (let index = 0; index < boundary.contour.length; index++) {
      const start = boundary.contour[index];
      const end = boundary.contour[(index + 1) % boundary.contour.length];
      const dx = end.x - start.x;
      const dy = end.y - start.y;
      const lengthSquared = dx * dx + dy * dy;
      const cuts = [0, 1];
      for (const region of excluded) {
        region.contour.forEach((a, edgeIndex) => {
          const b = region.contour[(edgeIndex + 1) % region.contour.length];
          const edgeX = b.x - a.x;
          const edgeY = b.y - a.y;
          const divisor = dx * edgeY - dy * edgeX;
          if (Math.abs(divisor) < 1e-9) {
            return;
          }
          const amount = ((a.x - start.x) * edgeY - (a.y - start.y) * edgeX) / divisor;
          if (amount > 0 && amount < 1) {
            cuts.push(amount);
          }
        });
      }
      cuts.sort((a, b) => a - b);
      const position = amount => ({ x: start.x + dx * amount, y: start.y + dy * amount });
      const projection = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared;
      for (let cut = 1; cut < cuts.length; cut++) {
        const low = cuts[cut - 1];
        const high = cuts[cut];
        if (!allowed(position((low + high) / 2))) {
          continue;
        }
        const margin = Math.min((high - low) / 2, 1 / Math.sqrt(lengthSquared));
        const candidate = position(Math.max(low + margin, Math.min(high - margin, projection)));
        const distance = Math.hypot(candidate.x - point.x, candidate.y - point.y);
        if (distance < nearestDistance && allowed(candidate)) {
          nearest = candidate;
          nearestDistance = distance;
        }
      }
    }
    return nearest;
  }

  function regionBoundary(region, nodes, children, graph, vector) {
    const childContours = children.map(child => [
      ...child.contour.flatMap(point => [{ x: point.x - 40, y: point.y }, { x: point.x + 40, y: point.y }, { x: point.x, y: point.y - 40 }, { x: point.x, y: point.y + 40 }]),
      { x: child.labelX - 12, y: child.labelY - 24 },
      { x: child.labelX + 300, y: child.labelY + 12 },
    ]);
    const members = new Set([...nodes.map(node => node.id), ...children.flatMap(child => [...child.members])]);
    const byId = new Map(graph.nodes.map(node => [node.id, node]));
    for (const edge of graph.edges) {
      if (!members.has(edge.source) || !members.has(edge.target)) {
        continue;
      }
      const source = byId.get(edge.target);
      const target = byId.get(edge.source);
      const path = graph.kind === 'circular' ? circularRoutePoints(source, target) : routePoints(source, target, edgeLane(edge));
      childContours.push(path);
    }
    const contentContour = boundaryContour(nodes, { flowX: vector.x, flowY: vector.y }, childContours);
    const contentWidth = Math.max(...contentContour.map(point => point.x)) - Math.min(...contentContour.map(point => point.x));
    const contentHeight = Math.max(...contentContour.map(point => point.y)) - Math.min(...contentContour.map(point => point.y));
    const padding = Math.max(96, Math.max(contentWidth, contentHeight) * 0.025);
    const contour = offsetContour(contentContour, padding);
    const top = Math.min(...contour.map(point => point.y));
    const topLeft = Math.min(...contour.filter(point => Math.abs(point.y - top) < 0.1).map(point => point.x));
    return {
      ...region, contour, contentContour, members,
      enclosureOpacity: graph.kind === 'circular' && !children.length ? 0 : 1,
      left: Math.min(...contour.map(point => point.x)), right: Math.max(...contour.map(point => point.x)),
      top, bottom: Math.max(...contour.map(point => point.y)), corner: 20,
      labelX: topLeft + 16, labelY: top, opacity: 1,
    };
  }

  function convexHull(points) {
    const sorted = [...points].sort((a, b) => a.x - b.x || a.y - b.y);
    const turn = (a, b, c) => (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    const half = list => {
      const result = [];
      for (const point of list) {
        while (result.length > 1 && turn(result[result.length - 2], result[result.length - 1], point) <= 0) {
          result.pop();
        }
        result.push(point);
      }
      return result;
    };
    return [...half(sorted).slice(0, -1), ...half([...sorted].reverse()).slice(0, -1)];
  }

  function visibilityBoundaries(regions, graph, vector) {
    const byId = new Map(graph.nodes.map(node => [node.id, node]));
    const regionById = new Map(regions.map(region => [region.id, region]));
    const ancestors = region => {
      const result = [];
      const visited = new Set([region.id]);
      let parent = regionById.get(region.parentId);
      while (parent && !visited.has(parent.id)) {
        visited.add(parent.id);
        result.push(parent.id);
        parent = regionById.get(parent.parentId);
      }
      return result;
    };
    const lineage = new Map(regions.map(region => [region.id, ancestors(region)]));
    const frames = new Map();
    for (const region of [...regions].sort((a, b) => lineage.get(a.id).length - lineage.get(b.id).length)) {
      const own = graph.nodes.filter(node => node.regionId === region.id);
      if (!own.length) {
        continue;
      }
      const ownFrame = regionBoundary(region, own, [], graph, vector);
      const parent = lineage.get(region.id).map(id => frames.get(id)).find(Boolean);
      if (!parent) {
        frames.set(region.id, ownFrame);
        continue;
      }
      const members = new Set([...ownFrame.members, ...parent.members]);
      const points = [
        ...ownFrame.contour, ...parent.contour,
        { x: parent.labelX - 12, y: parent.labelY - 48 },
        { x: parent.labelX + 300, y: parent.labelY + 12 },
      ];
      for (const edge of graph.edges) {
        if (!members.has(edge.source) || !members.has(edge.target)) {
          continue;
        }
        if (ownFrame.members.has(edge.source) === ownFrame.members.has(edge.target)) {
          continue;
        }
        const source = byId.get(edge.target);
        const target = byId.get(edge.source);
        points.push(...(graph.kind === 'circular' ? circularRoutePoints(source, target) : routePoints(source, target, edgeLane(edge))));
      }
      const contentContour = convexHull(points);
      const contentWidth = Math.max(...contentContour.map(point => point.x)) - Math.min(...contentContour.map(point => point.x));
      const contentHeight = Math.max(...contentContour.map(point => point.y)) - Math.min(...contentContour.map(point => point.y));
      const contour = offsetContour(contentContour, Math.max(96, Math.max(contentWidth, contentHeight) * 0.025));
      const label = contour.reduce((nearest, point) => {
        const distance = value => Math.hypot(value.x - ownFrame.labelX, value.y - ownFrame.labelY);
        return distance(point) < distance(nearest) ? point : nearest;
      });
      frames.set(region.id, {
        ...ownFrame, members, contour, contentContour, enclosureOpacity: 1,
        left: Math.min(...contour.map(point => point.x)), right: Math.max(...contour.map(point => point.x)),
        top: Math.min(...contour.map(point => point.y)), bottom: Math.max(...contour.map(point => point.y)),
        labelX: label.x + 16, labelY: label.y,
      });
    }
    return Array.from(frames.values());
  }

  function edgeLane(edge) {
    const identity = String(edge.id ?? JSON.stringify([edge.source, edge.target, edge.edgeType]));
    let lane = 0;
    for (let index = 0; index < identity.length; index++) {
      lane = (lane * 31 + identity.charCodeAt(index)) >>> 0;
    }
    return lane % 100;
  }

  function routePoints(source, target, index) {
    const dx = target.x - source.x;
    const dy = target.y - source.y;
    if (Math.abs(dy) < 1) {
      return [source, target];
    }
    const signX = dx >= 0 ? 1 : -1;
    const signY = dy >= 0 ? 1 : -1;
    const diagonal = Math.min(Math.abs(dx) * 0.38, Math.abs(dy));
    const turnX = source.x + dx * (0.36 + (index % 5) * 0.055);
    return [source, { x: turnX, y: source.y }, { x: turnX + signX * diagonal, y: source.y + signY * diagonal }, { x: turnX + signX * diagonal, y: target.y }, target];
  }

  function connectionRoutePoints(source, target, index, kind) {
    const sourceAnchor = { x: source.routeX ?? source.x, y: source.routeY ?? source.y };
    const targetAnchor = { x: target.routeX ?? target.x, y: target.routeY ?? target.y };
    const path = kind === 'circular' ? circularRoutePoints(sourceAnchor, targetAnchor) : routePoints(sourceAnchor, targetAnchor, index);
    return [{ x: source.x, y: source.y }, ...path, { x: target.x, y: target.y }].filter((point, position, points) => position === 0 || Math.hypot(point.x - points[position - 1].x, point.y - points[position - 1].y) > 0.001);
  }

  function circularRoutePoints(source, target) {
    const control = {
      x: (source.x + target.x) / 2 + (target.y - source.y) * 0.15,
      y: (source.y + target.y) / 2 - (target.x - source.x) * 0.15,
    };
    return Array.from({ length: 17 }, (_, index) => {
      const t = index / 16;
      const remaining = 1 - t;
      return {
        x: remaining * remaining * source.x + 2 * remaining * t * control.x + t * t * target.x,
        y: remaining * remaining * source.y + 2 * remaining * t * control.y + t * t * target.y,
      };
    });
  }

  const model = indexGraph(metroData);
  const retainedRegions = new Set();
  let retainedRegion = metroData.initialRegionId || model.root;
  while (retainedRegion && !retainedRegions.has(retainedRegion)) {
    retainedRegions.add(retainedRegion);
    retainedRegion = model.regionById.get(retainedRegion)?.parentId;
  }
  const extensionRegions = new Set(model.regions.filter(region => region.parentId && !retainedRegions.has(region.id)).map(region => region.id));
  const longestPath = (metroData.longestPath || []).filter((id, index, path) => index === 0 || id !== path[index - 1]);
  const chainIndices = new Map(longestPath.map((id, index) => [id, longestPath.length - index]));
  const chainEdges = longestPath.slice(1).flatMap((id, index) => {
    const edge = (model.outgoing.get(longestPath[index]) || []).find(item => item.target === id && item.edgeType !== 'deferrable');
    return edge ? [edge] : [];
  });
  const chainEntry = rootRoute(model, longestPath[0]);
  const chainNodes = [...(chainEntry?.nodes || []).filter(id => !chainIndices.has(id)), ...longestPath];
  const chainLinks = [...(chainEntry?.edges || []), ...chainEdges];
  const chainRelated = new Set(chainNodes);
  const chainActiveEdges = new Set(chainLinks.map(edge => edge.id));
  const regionLayouts = model.regions.length > 1 ? new Map(['full', 'radial', 'circular'].map(kind => [kind, layoutRegions(model, kind)])) : null;
  const positions = regionLayouts?.get('full').positions || layoutBindings(model);
  const radialPositions = regionLayouts?.get('radial').positions || layoutRadial(model);
  const circularPositions = regionLayouts?.get('circular').positions || layoutCircular(model);
  const packageGroups = groupPackages(model);
  const element = id => document.getElementById(id);
  const canvas = element('canvas');
  const context = canvas.getContext('2d', { alpha: false });
  const staticCanvas = document.createElement('canvas');
  const staticContext = staticCanvas.getContext('2d', { alpha: false });
  const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const state = {
    mode: metroData.initialRegionId ? 'full' : 'overview', selected: null, packageKey: null, packageMembers: null, query: '', sort: 'name', direction: 'both', depth: 1,
    limit: INITIAL_LIMIT, listLimit: LIST_PAGE, listFocus: -1, synthetic: false, defaults: false, extensions: true,
    edgeType: 'all', paused: motionPreference.matches, hovered: null, hoveredRegion: null, chain: false,
    expanded: false, chainLayout: 'full', flowDirection: 'auto',
    startHint: true,
    exploration: null,
    bindingRegion: null,
    routeTarget: null,
    returnView: null,
  };
  let view = { nodes: [], edges: [], total: 0, omitted: 0, kind: 'overview' };
  let camera = { x: 0, y: 0, scale: 1 };
  let width = 1;
  let height = 1;
  let pixelRatio = 1;
  let frame = null;
  let dirty = true;
  let lastPaint = 0;
  let screenNodes = [];
  let trafficPaths = [];
  let labelRects = [];
  let pointerPosition = null;
  let listNodes = [];
  let toastTimer;
  let localLayout = { key: null, positions: new Map() };
  let projectionCache = { key: null, model: null };
  let graphWithoutExtensions = null;
  let hintTimer;
  const TRANSITION_MS = 280;
  let scene = null;
  let graphTransition = null;
  let cameraTransition = null;
  let hasPainted = false;
  let canvasBounds = null;
  let attentionCache = null;

  function motionEnabled() {
    return !state.paused && !document.hidden;
  }

  function progress(transition, timestamp) {
    const elapsed = Math.max(0, Math.min(1, (timestamp - transition.start) / TRANSITION_MS));
    return 1 - Math.pow(1 - elapsed, 3);
  }

  function mix(from, to, amount) {
    return from + (to - from) * amount;
  }

  function regionDepth(id) {
    const visited = new Set();
    let region = model.regionById.get(id);
    let depth = 0;
    while (region?.parentId && !visited.has(region.id)) {
      visited.add(region.id);
      region = model.regionById.get(region.parentId);
      depth++;
    }
    return depth;
  }

  function flowDirection(kind = state.mode) {
    return state.flowDirection === 'auto' ? kind === 'route' ? 'tb' : 'lr' : state.flowDirection;
  }

  function flowVector(direction) {
    return { lr: { x: 1, y: 0 }, rl: { x: -1, y: 0 }, tb: { x: 0, y: 1 }, bt: { x: 0, y: -1 } }[direction];
  }

  function orientPoint(point, kind, direction) {
    if (kind === 'overview') {
      return { x: point.x, y: point.y };
    }
    if (kind === 'route') {
      if (direction === 'lr' || direction === 'rl') {
        return { x: point.y * 3 * (direction === 'lr' ? 1 : -1), y: point.x };
      }
      return { x: point.x, y: point.y * (direction === 'tb' ? 1 : -1) };
    }
    if (direction === 'lr' || direction === 'rl') {
      return { x: point.x * (direction === 'lr' ? 1 : -1), y: point.y };
    }
    const round = kind === 'radial' || kind === 'circular';
    return { x: point.y * (round ? -1 : 2.5), y: point.x * (round ? 1 : 2.5) * (direction === 'tb' ? 1 : -1) };
  }

  function orientGraph(graph) {
    const direction = flowDirection(graph.kind);
    const vector = flowVector(direction);
    const oriented = {
      ...graph, flowDirection: direction,
      nodes: graph.nodes.map(node => ({
        ...node, ...orientPoint(node, graph.kind, direction), flowX: vector.x, flowY: vector.y,
        boundaryWeight: graph.kind === 'circular' ? 0 : 1,
        labelSide: direction === 'lr' && node.isRootMember || direction === 'rl' && node.isGraphInput ? 'left' : 'right',
        labelBelow: graph.kind === 'route' && vector.y === 0,
      })),
    };
    const boundaries = regionBoundaries(oriented);
    const frames = new Map(boundaries.map(boundary => [boundary.id, boundary]));
    return {
      ...oriented, boundaries,
      nodes: oriented.nodes.map(node => {
        const anchored = { ...node, routeX: node.x, routeY: node.y };
        const boundary = frames.get(node.regionId);
        if (!boundary || !node.boundaryWeight || !(node.isGraphInput || node.isRootMember)) {
          return anchored;
        }
        const outward = node.isGraphInput ? -1 : 1;
        const point = projectBoundaryNode(node, boundary, boundaries, node, { x: vector.x * outward, y: vector.y * outward });
        return point ? { ...anchored, ...point } : anchored;
      }),
    };
  }

  function regionBoundaries(graph) {
    if (graph.boundaries) {
      return graph.boundaries;
    }
    const vector = flowVector(graph.flowDirection || flowDirection(graph.kind));
    if (graph.kind === 'overview') {
      const main = model.regions.find(region => !region.parentId) || model.regions[0];
      const boundary = regionBoundary(main, graph.nodes, [], graph, vector);
      return [{ ...boundary, enclosureOpacity: 0, showTitle: false }];
    }
    return visibilityBoundaries(model.regions.filter(region => state.extensions || !extensionRegions.has(region.id)), graph, vector);
  }

  function rootDependencyConnections(graph, root) {
    const activeEdges = new Set();
    const visited = new Set([root]);
    const queue = [root];
    for (let index = 0; index < queue.length; index++) {
      for (const edge of graph.outgoing.get(queue[index]) || []) {
        activeEdges.add(edge.id);
        if (!visited.has(edge.target)) {
          visited.add(edge.target);
          queue.push(edge.target);
        }
      }
    }
    return activeEdges;
  }

  function attentionFor(graph, candidate) {
    if (attentionCache?.graph === graph && attentionCache.candidate === candidate) {
      return attentionCache.result;
    }
    if (graph.chain) {
      const result = { attention: 'longest-chain', related: chainRelated, activeEdges: chainActiveEdges };
      attentionCache = { graph, candidate, result };
      return result;
    }
    const attention = graph.kind === 'route' || !graph.nodes.some(node => node.id === candidate) ? null : candidate;
    const related = new Set(attention ? [attention] : []);
    const selectedNode = model.byId.get(attention);
    const inputSelected = selectedNode?.isGraphInput;
    const rootSelected = selectedNode?.isRootMember && graph.kind !== 'overview';
    const traceRoot = attention && !inputSelected && graph.kind !== 'overview';
    let activeEdges = new Set();
    if (rootSelected) {
      activeEdges = rootDependencyConnections(displayGraph(), attention);
    } else if (traceRoot) {
      activeEdges = rootConnections(displayGraph(), attention);
    }
    if (attention) {
      for (const edge of graph.edges) {
        const immediateNeighbor = edge.source === attention || edge.target === attention;
        if (!rootSelected && immediateNeighbor) {
          activeEdges.add(edge.id);
        }
        if (activeEdges.has(edge.id)) {
          related.add(edge.source);
          related.add(edge.target);
        }
      }
    }
    const result = { attention, related, activeEdges };
    attentionCache = { graph, candidate, result };
    return result;
  }

  function prepareScene() {
    const { attention, related, activeEdges } = attentionFor(view, state.selected);
    const crowded = view.edges.length > 450;
    const boundaries = regionBoundaries(view);
    const main = boundaries.find(region => !region.parentId) || boundaries[0];
    return {
      nodes: view.nodes.map(node => ({ ...node, opacity: 1, emphasis: !attention || related.has(node.id) ? 1 : 0.28, renderKind: view.kind })),
      edges: view.edges.map(edge => {
        const identity = String(edge.id ?? JSON.stringify([edge.source, edge.target, edge.edgeType]));
        const active = !attention || activeEdges.has(edge.id);
        return {
          ...edge, opacity: 1, renderKind: view.kind, renderIndex: edgeLane(edge),
          renderKey: identity + ':' + (view.kind === 'circular'),
          emphasis: active ? (attention || view.kind === 'route' ? 0.88 : crowded ? 0.3 : 0.58) : 0.07,
        };
      }),
      boundary: main ? { ...main } : null,
      regionFrames: boundaries.filter(region => region.id !== main?.id),
      radialOpacity: view.kind === 'radial' && !regionLayouts ? 1 : 0,
    };
  }

  function transitionPairs(before, after, key) {
    const previous = new Map(before.map(item => [item[key], item]));
    const pairs = after.map(item => {
      const from = previous.get(item[key]) || { ...item, opacity: 0 };
      previous.delete(item[key]);
      return { from, to: item };
    });
    for (const item of previous.values()) {
      if (item.opacity > 0.001) {
        pairs.push({ from: item, to: { ...item, opacity: 0, exiting: true } });
      }
    }
    return pairs;
  }

  function interpolateBoundary(from, to, amount) {
    const interpolateContour = (before, after) => {
      const points = [];
      for (const a of before) {
        for (const b of after) {
          points.push({ x: mix(a.x, b.x, amount), y: mix(a.y, b.y, amount) });
        }
      }
      return convexHull(points);
    };
    return {
      ...to,
      ...Object.fromEntries(['left', 'right', 'top', 'bottom', 'corner', 'labelX', 'labelY', 'opacity', 'enclosureOpacity'].map(key => [key, mix(from[key], to[key], amount)])),
      contour: interpolateContour(from.contour, to.contour),
      contentContour: interpolateContour(from.contentContour, to.contentContour),
    };
  }

  function sampleScene(timestamp) {
    if (!graphTransition) {
      return scene;
    }
    const amount = progress(graphTransition, timestamp);
    if (amount === 1) {
      graphTransition = null;
      return scene;
    }
    const interpolate = ({ from, to }) => ({
      ...to, opacity: mix(from.opacity, to.opacity, amount), emphasis: mix(from.emphasis, to.emphasis, amount),
    });
    const frames = graphTransition.boundaries.map(({ from, to }) => interpolateBoundary(from, to, amount));
    const primaryId = scene.boundary?.id || graphTransition.from.boundary?.id;
    const boundary = frames.find(region => region.id === primaryId) || null;
    const regionFrames = frames.filter(region => region !== boundary);
    const boundaries = new Map(frames.map(region => [region.id, region]));
    const nodes = graphTransition.nodes.map(pair => {
      const node = {
        ...interpolate(pair), x: mix(pair.from.x, pair.to.x, amount), y: mix(pair.from.y, pair.to.y, amount),
        routeX: mix(pair.from.routeX, pair.to.routeX, amount), routeY: mix(pair.from.routeY, pair.to.routeY, amount),
        flowX: mix(pair.from.flowX, pair.to.flowX, amount), flowY: mix(pair.from.flowY, pair.to.flowY, amount),
        boundaryWeight: mix(pair.from.boundaryWeight, pair.to.boundaryWeight, amount),
      };
      const region = boundaries.get(node.regionId);
      if (region && node.boundaryWeight > 0 && (node.isGraphInput || node.isRootMember)) {
        const outward = node.isGraphInput ? -1 : 1;
        const point = projectBoundaryNode(node, region, frames, { x: node.routeX, y: node.routeY }, { x: node.flowX * outward, y: node.flowY * outward });
        if (point) {
          node.x = mix(node.routeX, point.x, node.boundaryWeight);
          node.y = mix(node.routeY, point.y, node.boundaryWeight);
        }
      }
      return node;
    });
    return { nodes, edges: graphTransition.edges.map(interpolate), boundary, regionFrames, radialOpacity: mix(graphTransition.from.radialOpacity, scene.radialOpacity, amount) };
  }

  function visibleScene(timestamp) {
    const current = sampleScene(timestamp);
    if (!current || !state.hovered) {
      return current;
    }
    const { attention, related, activeEdges } = attentionFor(view, state.hovered);
    return {
      ...current,
      nodes: current.nodes.map(node => ({ ...node, emphasis: !attention || related.has(node.id) ? 1 : 0.28 })),
      edges: current.edges.map(edge => ({ ...edge, emphasis: !attention || activeEdges.has(edge.id) ? 0.88 : 0.07 })),
    };
  }

  function transitionView(from) {
    const start = performance.now();
    scene = prepareScene();
    if (!from || !hasPainted || !motionEnabled()) {
      graphTransition = null;
      return;
    }
    graphTransition = {
      start, from,
      nodes: transitionPairs(from.nodes, scene.nodes, 'id'),
      edges: transitionPairs(from.edges, scene.edges, 'renderKey'),
      boundaries: transitionPairs([from.boundary, ...(from.regionFrames || [])].filter(Boolean), [scene.boundary, ...scene.regionFrames].filter(Boolean), 'id'),
    };
  }

  function sampleCamera(timestamp) {
    if (!cameraTransition) {
      return;
    }
    const amount = progress(cameraTransition, timestamp);
    const { from, to } = cameraTransition;
    camera = { x: mix(from.x, to.x, amount), y: mix(from.y, to.y, amount), scale: mix(from.scale, to.scale, amount) };
    if (amount === 1) {
      cameraTransition = null;
    }
  }

  function moveCamera(target) {
    const start = performance.now();
    sampleCamera(start);
    if (hasPainted && motionEnabled()) {
      cameraTransition = { from: { ...camera }, to: target, start };
    } else {
      camera = target;
      cameraTransition = null;
    }
    invalidate();
  }

  function stopCamera() {
    sampleCamera(performance.now());
    cameraTransition = null;
  }

  function finishTransitions() {
    if (cameraTransition) {
      camera = cameraTransition.to;
      cameraTransition = null;
    }
    graphTransition = null;
  }

  function layoutNeighborhood(ids, seeds, key) {
    if (regionLayouts) {
      return new Map(ids.map(id => [id, positions.get(id)]));
    }
    const layoutModel = displayGraph();
    if (localLayout.key !== key) {
      localLayout = { key, positions: new Map() };
    }
    const included = new Set(ids);
    const layoutSeeds = seeds.filter(id => !model.byId.get(id)?.isGraphInput || id === state.exploration?.anchor);
    const ranks = new Map((layoutSeeds.length ? layoutSeeds : seeds).filter(id => included.has(id)).map(id => [id, 0]));
    const queue = Array.from(ranks.keys());
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      for (const edge of [...(layoutModel.outgoing.get(id) || []), ...(layoutModel.incoming.get(id) || [])]) {
        const target = edge.source === id ? edge.target : edge.source;
        if (included.has(target) && !ranks.has(target)) {
          ranks.set(target, ranks.get(id) + (edge.source === id ? 1 : -1));
          queue.push(target);
        }
      }
    }
    const columns = new Map();
    const occupied = new Map();
    for (const point of localLayout.positions.values()) {
      const rank = point.x / COLUMN_GAP;
      if (!occupied.has(rank)) {
        occupied.set(rank, new Set());
      }
      occupied.get(rank).add(point.y / ROW_GAP);
    }
    for (const id of ids) {
      if (localLayout.positions.has(id)) {
        continue;
      }
      const rank = ranks.get(id) || 0;
      if (!columns.has(rank)) {
        columns.set(rank, []);
      }
      columns.get(rank).push(id);
    }
    for (const [rank, column] of columns) {
      column.sort((a, b) => Number(b === state.selected) - Number(a === state.selected) || a.localeCompare(b));
      const rows = occupied.get(rank) || new Set();
      column.forEach((id, index) => {
        let row = index - Math.floor((column.length - 1) / 2);
        if (id === state.selected) {
          row = 0;
        }
        let distance = 0;
        while (rows.has(row)) {
          distance++;
          row += distance % 2 ? distance : -distance;
        }
        rows.add(row);
        localLayout.positions.set(id, { x: rank * COLUMN_GAP, y: row * ROW_GAP });
      });
    }
    const points = new Map(ids.map(id => [id, localLayout.positions.get(id)]));
    positionRootMembers(model, points);
    positionGraphInputs(model, points);
    return flowPositions(points);
  }

  const text = (tag, className, value) => {
    const node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    node.textContent = displayText(value);
    return node;
  };
  const button = (className, label, action) => {
    const node = text('button', className, label);
    node.type = 'button';
    node.addEventListener('click', action);
    return node;
  };
  const color = node => node.itemStyle?.color || metroData.categories?.[node.category]?.itemStyle?.color || PALETTE[node.category % PALETTE.length] || PALETTE[1];
  const humanize = value => String(value || '').replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2');
  const shortName = key => model.byId.get(key)?.name || displayKey(key);

  function displayKey(key) {
    let type = String(key ?? '');
    const qualifier = qualifierCaption(type);
    while (type.startsWith('@')) {
      const match = type.match(/^@[\w.$]+/);
      if (!match) {
        break;
      }
      type = type.slice(annotationEnd(type, match[0].length)).trimStart();
    }
    const name = type.replace(/[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+/g, token => {
      if (metroData.typeNames?.[token]) {
        return metroData.typeNames[token];
      }
      const parts = token.split('.');
      const firstClass = parts.findIndex(part => /^[A-Z]/.test(part));
      return firstClass < 0 ? parts[parts.length - 1] : parts.slice(firstClass).join('.');
    });
    return qualifier ? qualifier + ' ' + name : name;
  }
  const packageName = pkg => pkg || 'Unassigned package';

  function announce(message) {
    element('live-region').textContent = displayText(message);
  }

  function toast(message) {
    clearTimeout(toastTimer);
    element('toast').textContent = message;
    element('toast').hidden = false;
    toastTimer = setTimeout(() => { element('toast').hidden = true; }, 2400);
    announce(message);
  }

  function inGraphScope(node) {
    return state.extensions || !extensionRegions.has(node?.regionId);
  }

  function scopedGraph() {
    if (state.extensions) {
      return model;
    }
    if (!graphWithoutExtensions) {
      const visible = new Set(model.nodes.filter(inGraphScope).map(node => node.id));
      graphWithoutExtensions = projectGraph(model, visible, new Set());
    }
    return graphWithoutExtensions;
  }

  function unavailableRouteMessage() {
    if (!state.extensions && extensionRegions.size) {
      return 'No root route is visible with extensions hidden. Show extensions or use Connections to inspect this binding.';
    }
    return 'No root route is recorded for this binding. Use Connections to inspect its dependencies and consumers.';
  }

  function enableExtensions() {
    state.extensions = true;
    element('show-extensions').checked = true;
  }

  function viewOutsideScope(saved) {
    if (!saved) {
      return false;
    }
    if (saved.chain && chainNodes.some(id => !inGraphScope(model.byId.get(id)))) {
      return true;
    }
    if (saved.mode === 'explore') {
      return !inGraphScope(model.byId.get(saved.exploration?.anchor));
    }
    if (saved.mode === 'route' && !saved.chain) {
      const target = model.byId.get(saved.routeTarget || state.selected);
      if (!inGraphScope(target)) {
        return true;
      }
      return !target?.isGraphInput && !rootRoute(scopedGraph(), target?.id);
    }
    return false;
  }

  function setExtensions(visible) {
    state.extensions = visible;
    if (viewOutsideScope(state)) {
      state.mode = 'full';
      state.chain = false;
      state.exploration = null;
      state.routeTarget = null;
      state.returnView = null;
    }
    if (!inGraphScope(model.byId.get(state.selected))) {
      state.selected = null;
    }
    if (viewOutsideScope(state.returnView)) {
      state.returnView = null;
    }
    if (state.returnView) {
      state.returnView.width = 0;
    }
    renderDetails();
    renderBindings();
    refreshView();
    resize();
    fitView();
    announce(visible ? 'Graph extensions shown' : 'Graph extensions hidden');
  }

  function passesDisplay(node) {
    if (!inGraphScope(node)) {
      return false;
    }
    const anchor = state.mode === 'explore' ? state.exploration?.anchor : null;
    if (node.id === state.selected || node.id === anchor || node.isGraph || state.chain && chainRelated.has(node.id)) {
      return true;
    }
    if (!state.defaults && node.isDefaultValue) {
      return false;
    }
    if (!state.synthetic && node.synthetic && !node.isDefaultValue) {
      return false;
    }
    return true;
  }

  function displayGraph() {
    const anchor = state.mode === 'explore' ? state.exploration?.anchor : null;
    const key = JSON.stringify([state.synthetic, state.defaults, state.extensions, state.selected, anchor, state.chain]);
    if (projectionCache.key !== key) {
      const scoped = scopedGraph();
      const visible = new Set(scoped.nodes.filter(passesDisplay).map(node => node.id));
      const collapsible = new Set(scoped.nodes.filter(node => node.synthetic && !visible.has(node.id) && !node.isDefaultValue).map(node => node.id));
      projectionCache = { key, model: projectGraph(scoped, visible, collapsible) };
    }
    return projectionCache.model;
  }

  function dismissStartHint() {
    if (state.startHint) {
      state.startHint = false;
      clearTimeout(hintTimer);
      invalidate();
    }
  }

  function packageOverview() {
    const groups = [];
    const byPackage = new Map();
    for (const original of packageGroups) {
      const visible = original.packages.flatMap(pkg => model.packages.get(pkg) || []).filter(passesDisplay);
      if (!visible.length) {
        continue;
      }
      const group = { ...original, name: packageName(original.pkg), members: visible, count: visible.length, group: true };
      groups.push(group);
      for (const pkg of original.packages) {
        byPackage.set(pkg, group);
      }
    }
    const edgeMap = new Map();
    for (const edge of displayGraph().links) {
      const sourceNode = model.byId.get(edge.source);
      const targetNode = model.byId.get(edge.target);
      if (!passesDisplay(sourceNode) || !passesDisplay(targetNode)) {
        continue;
      }
      const source = byPackage.get(sourceNode.pkg || '');
      const target = byPackage.get(targetNode.pkg || '');
      if (!source || !target || source === target) {
        continue;
      }
      const key = JSON.stringify([source.id, target.id]);
      if (!edgeMap.has(key)) {
        edgeMap.set(key, { id: 'package:' + key, source: source.id, target: target.id, count: 0, edgeType: 'package', lineStyle: { color: source.color } });
      }
      edgeMap.get(key).count++;
    }
    return { nodes: groups, edges: Array.from(edgeMap.values()), total: groups.length, omitted: 0, kind: 'overview' };
  }

  function beginNeighborhood(anchor, packages = null) {
    const input = model.byId.get(anchor)?.isGraphInput;
    state.direction = input ? 'consumers' : 'dependencies';
    state.depth = 1;
    state.limit = INITIAL_LIMIT;
    element('direction').value = state.direction;
    element('depth').value = String(state.depth);
    const roots = anchor === model.root && model.rootMembers.length ? model.rootMembers.map(node => node.id) : [anchor];
    const seeds = packages ? packages.flatMap(pkg => model.packages.get(pkg) || []).map(node => node.id) : roots.filter(Boolean);
    state.exploration = { anchor, packages, expanded: new Set(seeds) };
  }

  function exploredNeighborhood(projected) {
    if (!state.exploration) {
      beginNeighborhood(state.selected || model.root, state.selected ? null : state.packageMembers);
    }
    const { anchor, packages, expanded } = state.exploration;
    const graph = anchor === model.root;
    const seeds = packages ? packages.flatMap(pkg => model.packages.get(pkg) || []).filter(passesDisplay).map(node => node.id) : anchor ? [anchor] : model.nodes.map(node => node.id);
    if (graph) {
      seeds.push(...model.rootMembers.map(node => node.id), ...model.graphInputs.map(node => node.id));
    }
    const input = model.byId.get(anchor)?.isGraphInput;
    const trace = anchor && !input ? rootRoute(projected, anchor)?.nodes || [] : [];
    const seedSet = new Set(seeds);
    const visited = new Set([...seeds, ...trace]);
    const queue = Array.from(visited);
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index];
      if (!expanded.has(id)) {
        continue;
      }
      const node = model.byId.get(id);
      const direction = node?.isGraphInput ? 'consumers' : state.direction;
      const depth = seedSet.has(id) ? state.depth : 1;
      for (const neighbor of neighborhood(projected, id, direction, depth)) {
        if (!visited.has(neighbor)) {
          visited.add(neighbor);
          queue.push(neighbor);
        }
      }
    }
    return { candidates: Array.from(visited), seeds, trace, visited };
  }

  function toggleStation(id) {
    if (state.mode !== 'explore') {
      if (state.selected === id) {
        clearSelection();
      } else {
        selectNode(id);
      }
      return;
    }
    const projected = displayGraph();
    exploredNeighborhood(projected);
    const expanded = state.exploration.expanded;
    const collapsing = state.selected === id && expanded.has(id);
    if (collapsing) {
      expanded.delete(id);
      const { visited } = exploredNeighborhood(projected);
      for (const previous of expanded) {
        if (!visited.has(previous)) {
          expanded.delete(previous);
        }
      }
    } else {
      expanded.add(id);
    }
    state.selected = id;
    state.chain = false;
    dismissStartHint();
    renderDetails();
    renderBindings();
    refreshView();
    announce((collapsing ? 'Collapsed ' : 'Expanded ') + shortName(id));
  }

  function buildView() {
    if (state.mode === 'overview') {
      return packageOverview();
    }
    let candidates;
    const pinnedTrace = new Set();
    const projected = displayGraph();
    let seeds = state.selected ? [state.selected] : [];
    let routeEdges = null;
    let routeMissing = false;
    const routeTarget = state.routeTarget || state.selected || model.root;
    const inputRoute = state.mode === 'route' && !state.chain && model.byId.get(routeTarget)?.isGraphInput;
    if (state.mode === 'route') {
      if (inputRoute) {
        candidates = [routeTarget];
        routeEdges = [];
      } else if (state.chain) {
        candidates = chainNodes;
        routeEdges = chainLinks;
      } else {
        const route = rootRoute(scopedGraph(), routeTarget);
        candidates = route?.nodes || (routeTarget ? [routeTarget] : []);
        routeEdges = route?.edges || [];
        routeMissing = !route;
      }
    } else if (state.mode === 'full' || state.mode === 'radial' || state.mode === 'circular') {
      candidates = model.nodes.map(node => node.id);
    } else {
      const exploration = exploredNeighborhood(projected);
      candidates = exploration.candidates;
      seeds = exploration.seeds;
      exploration.trace.forEach(id => pinnedTrace.add(id));
    }
    const stationCandidates = candidates.filter(id => !model.byId.get(id)?.isGraph && inGraphScope(model.byId.get(id)));
    const eligible = state.mode === 'route' ? stationCandidates : stationCandidates.filter(id => passesDisplay(model.byId.get(id)));
    const anchor = state.exploration?.anchor;
    const countedAnchor = pinnedTrace.has(anchor) && !model.byId.get(anchor)?.isGraph;
    let remaining = state.limit - Number(countedAnchor);
    const bounded = state.mode === 'explore' ? eligible.filter(id => {
      if (pinnedTrace.has(id)) {
        return true;
      }
      return remaining-- > 0;
    }) : eligible;
    const stationIds = state.mode === 'route' ? [...bounded].reverse() : bounded;
    const included = new Set(stationIds);
    const seedSet = new Set(seeds);
    const layoutKey = state.exploration || state.selected || model.root;
    const layouts = { full: positions, radial: radialPositions, circular: circularPositions };
    const layoutIds = candidates.includes(model.root) && model.byId.get(model.root)?.isGraph ? [model.root, ...bounded] : bounded;
    const activePositions = state.mode === 'explore' ? layoutNeighborhood(layoutIds, seeds, layoutKey) : layouts[state.mode] || positions;
    const nodes = stationIds.map((id, index) => {
      const node = model.byId.get(id) || { id, fullKey: id, name: shortName(id), kind: 'Unrepresented chain key', itemStyle: { color: '#8d9daf' }, unavailable: true };
      const position = state.mode === 'route'
        ? { x: regionDepth(node.regionId) * 120, y: index * ROUTE_GAP }
        : activePositions.get(id);
      const routeIndex = state.chain ? chainIndices.get(id) : state.mode === 'route' ? index + 1 : undefined;
      return { ...node, ...position, isEntryPoint: projected.entryPoints.has(id), contextNode: state.mode === 'explore' && Boolean(state.exploration?.packages) && !seedSet.has(id), routeIndex, chainNode: state.chain && chainRelated.has(id) };
    });
    const edges = (routeEdges || projected.links).filter(edge => {
      if (!included.has(edge.source) || !included.has(edge.target)) {
        return false;
      }
      if (edge.presentationOnly || edge.rootMembership) {
        return false;
      }
      const accessor = state.edgeType === 'accessor' && edge.rootKind === 'accessor';
      return state.mode === 'route' || state.chain && chainActiveEdges.has(edge.id) || state.edgeType === 'all' || accessor || edge.edgeType === state.edgeType;
    });
    const hiddenConnections = projected.links.filter(edge => !edge.rootMembership && !model.byId.get(edge.source)?.isGraph && included.has(edge.source) !== included.has(edge.target)).length;
    return { nodes, edges, total: eligible.length, omitted: eligible.length - bounded.length, kind: state.mode, hiddenConnections, routeMissing, inputRoute, filtered: stationCandidates.length - eligible.length, chain: state.chain, chainGaps: state.chain ? Math.max(0, longestPath.length - 1 - chainEdges.length) : 0 };
  }

  function refreshView(fit = false) {
    const previousScene = visibleScene(performance.now());
    view = orientGraph(buildView());
    const selection = model.byId.get(state.selected);
    element('app').classList.toggle('has-selection', Boolean(selection));
    element('selection-toolbar').hidden = !selection;
    element('selected-binding-name').textContent = selection?.name || '';
    element('selected-binding-name').title = selection ? displayText(selection.fullKey) + ' · Show on graph' : '';
    state.hovered = null;
    state.hoveredRegion = null;
    transitionView(previousScene);
    element('canvas-tooltip').hidden = true;
    document.querySelectorAll('[data-mode]').forEach(node => {
      const focusedSelection = node.dataset.mode === 'explore' ? state.exploration?.anchor === state.selected : state.routeTarget === state.selected;
      const selectionAction = Boolean(node.closest('#selection-toolbar'));
      const active = node.dataset.mode === state.mode && (!selectionAction || Boolean(selection && focusedSelection));
      node.classList.toggle('active', active);
      node.setAttribute('aria-pressed', String(active));
      if (node.hasAttribute('data-context-view')) {
        node.hidden = !active;
      }
      if (selectionAction) {
        const routeAction = node.dataset.mode === 'route';
        const action = routeAction ? 'Route from root to ' : 'Focus connections of ';
        const unavailable = routeAction && selection && !rootRoute(scopedGraph(), selection.id);
        node.hidden = Boolean(routeAction && selection?.isGraphInput);
        node.disabled = Boolean(unavailable);
        node.title = unavailable ? unavailableRouteMessage() : selection ? action + selection.name : '';
        node.setAttribute('aria-label', selection ? action + selection.name : node.textContent);
      }
    });
    const focusedView = state.mode === 'explore' || state.mode === 'route';
    const back = element('back-to-graph');
    const visibleRoots = scopedGraph().entryPoints.size;
    element('show-entry-points').disabled = visibleRoots === 0;
    element('show-entry-points').title = visibleRoots + ' root bindings in visible graphs';
    back.hidden = !focusedView || state.chain;
    element('show-root').hidden = !back.hidden;
    const previousName = { overview: 'overview', full: 'full graph', radial: 'radial graph', circular: 'circular graph', explore: 'connections', route: 'route' }[state.returnView?.mode || 'full'];
    back.textContent = '← Back to ' + previousName;
    element('longest-path').classList.toggle('active', state.chain);
    element('longest-path').setAttribute('aria-pressed', String(state.chain));
    element('chain-controls').hidden = !state.chain;
    element('isolate-chain').textContent = state.mode === 'route' ? 'Show in graph' : 'Isolate chain';
    element('isolate-chain').setAttribute('aria-pressed', String(state.mode === 'route' && state.chain));
    element('chain-description').textContent = longestPath.length + ' bindings · ' + Math.max(0, longestPath.length - 1) + ' dependency edges. Deferred edges are excluded; aliases count as steps.' + (state.mode === 'route' ? ' The root after the numbered chain provides context.' : ' Numbered bindings trace the chain toward its root.');
    element('explorer-controls').hidden = state.mode !== 'explore';
    element('reading-direction').hidden = true;
    element('input-legend').hidden = !model.graphInputs.length || state.mode === 'overview' || state.mode === 'route';
    element('reading-direction').textContent = 'Graph inputs → Bindings → Roots';
    element('flow-direction').value = state.flowDirection;
    element('flow-direction').disabled = state.mode === 'overview';
    element('load-more').hidden = view.omitted === 0;
    element('load-more').textContent = 'Show ' + Math.min(INITIAL_LIMIT, view.omitted) + ' more';
    let title;
    let subtitle;
    if (state.mode === 'overview') {
      title = 'Package overview';
      subtitle = 'Select a package group to see its bindings and their immediate connections.';
    } else if (state.mode === 'route') {
      const target = model.byId.get(state.routeTarget || state.selected);
      title = view.inputRoute ? 'Graph input' : state.chain ? 'Longest dependency chain' : 'Route · ' + (target?.name || 'root');
      subtitle = view.inputRoute ? 'Supplied when ' + (model.regionById.get(target?.regionId)?.name || shortName(model.root)) + ' is created. Use Connections to inspect its consumers.' : view.routeMissing ? unavailableRouteMessage() : 'Complete recorded route. Select any binding to inspect it. Clear the selection to return to Overview.';
    } else if (state.mode === 'full') {
      title = 'Full graph';
      subtitle = 'Graph inputs → bindings → accessors and injectors. Extension outlines include their parent bindings.';
    } else if (state.mode === 'radial') {
      title = 'Radial graph';
      subtitle = 'Dependency depth forms rings. Inputs and roots follow the selected direction.';
    } else if (state.mode === 'circular') {
      title = 'Circular graph';
      subtitle = 'All bindings on one circle, with their dependency connections.';
    } else {
      const anchor = model.byId.get(state.exploration?.anchor);
      const packages = state.exploration?.packages;
      title = anchor?.isGraph ? 'Graph roots' : packages ? packageName(state.packageKey) : 'Connections · ' + (anchor?.name || 'graph');
      subtitle = 'Select a binding to expand its connections. Click the selected binding again to collapse. Clear the selection to return to Overview.';
    }
    if (state.chain) {
      if (state.mode !== 'route') {
        title += ' · longest chain';
      }
      const entry = chainEntry?.nodes.map(id => model.byId.get(id)).find(node => node?.isRootMember);
      const owner = shortName(model.root || metroData.graphName);
      const origin = entry ? owner + '.' + entry.name : owner;
      subtitle = shortName(longestPath[longestPath.length - 1]) + ' → … → ' + shortName(longestPath[0]) + ' → ' + origin;
      element('chain-context').textContent = subtitle;
    }
    element('view-title').textContent = title;
    element('view-subtitle').textContent = subtitle;
    const rootCount = view.nodes.filter(node => node.isRootMember).length;
    const shownBindings = view.nodes.filter(node => !node.isRootMember && node.kind !== 'Graph').length;
    const unit = state.mode === 'overview' ? 'package groups' : 'bindings' + (rootCount ? ' · ' + rootCount + ' roots' : '');
    element('shown-count').textContent = (state.mode === 'overview' ? view.nodes.length : shownBindings).toLocaleString() + (view.omitted ? ' / ' + view.total.toLocaleString() : '') + ' ' + unit + ' · ' + view.edges.filter(edge => !edge.rootMembership).length.toLocaleString() + ' connections';
    const notes = [];
    if (!state.extensions && extensionRegions.size) {
      notes.push(extensionRegions.size + ' graph extensions hidden');
    }
    if (view.omitted) {
      notes.push(view.omitted.toLocaleString() + ' more bindings available');
    }
    if (view.filtered) {
      notes.push(view.filtered.toLocaleString() + ' hidden by display filters');
    }
    if (view.hiddenConnections) {
      notes.push(view.hiddenConnections.toLocaleString() + ' connections continue outside this view');
    }
    const collapsedConnections = view.edges.filter(edge => edge.collapsedVia?.length).length;
    if (collapsedConnections) {
      notes.push(collapsedConnections + ' connections pass through hidden bindings');
    }
    if (view.chainGaps) {
      notes.push(view.chainGaps + ' chain connections are absent from the visualization; their gaps are left disconnected');
    }
    if (view.kind === 'route' && view.nodes.length > 12) {
      notes.push('Drag the map to follow the complete route');
    }
    if (view.kind === 'full' || view.kind === 'radial' || view.kind === 'circular' || (view.kind === 'explore' && view.nodes.length > 12)) {
      notes.push('Pan to browse · Fit for the whole view');
    }
    element('canvas-status').textContent = notes.join(' · ') || (state.mode === 'overview' ? 'Lines connect packages. Counts summarize their bindings.' : 'Arrows point from dependencies to their consumers.');
    if (fit) {
      resize();
      openView();
    } else {
      invalidate();
    }
  }

  function rememberView() {
    state.returnView = {
      mode: state.mode, exploration: state.exploration, routeTarget: state.routeTarget,
      direction: state.direction, depth: state.depth, limit: state.limit,
      packageKey: state.packageKey, packageMembers: state.packageMembers,
      chain: state.chain, chainLayout: state.chainLayout,
      width, height, flowDirection: state.flowDirection,
      camera: { ...(cameraTransition?.to || camera) },
    };
  }

  function switchMode(mode) {
    const focused = mode === 'explore' || mode === 'route';
    if (mode === 'route' && !rootRoute(scopedGraph(), state.selected)) {
      return;
    }
    const sameTarget = mode === 'explore' ? state.exploration?.anchor === state.selected : state.routeTarget === state.selected;
    if (focused && mode === state.mode && sameTarget && !state.chain) {
      return;
    }
    if (focused) {
      rememberView();
    }
    state.mode = mode;
    if (mode === 'explore') {
      beginNeighborhood(state.selected || model.root);
    } else if (mode === 'route') {
      state.routeTarget = state.selected;
    }
    const graphLayout = ['full', 'radial', 'circular'].includes(mode);
    state.chain = state.chain && graphLayout;
    if (graphLayout) {
      state.chainLayout = mode;
    }
    state.limit = INITIAL_LIMIT;
    refreshView(focused);
    if (!focused) {
      resize();
      fitView();
    }
    renderDetails();
  }

  function returnToGraph() {
    const previous = state.returnView;
    state.returnView = null;
    state.selected = null;
    state.chain = previous?.chain || false;
    state.mode = previous?.mode || 'full';
    state.exploration = previous?.exploration || null;
    state.routeTarget = previous?.routeTarget || null;
    if (previous) {
      state.direction = previous.direction;
      state.depth = previous.depth;
      state.limit = previous.limit;
      state.packageKey = previous.packageKey;
      state.packageMembers = previous.packageMembers;
      state.chainLayout = previous.chainLayout;
    }
    element('direction').value = state.direction;
    element('depth').value = String(state.depth);
    renderDetails();
    renderPackages();
    renderBindings();
    refreshView();
    resize();
    const sameViewport = previous?.width === width && previous?.height === height;
    if (sameViewport && previous.flowDirection === state.flowDirection) {
      moveCamera(previous.camera);
    } else {
      fitView();
    }
  }

  function selectNode(id, fromList = false) {
    if (!model.byId.has(id)) {
      return;
    }
    if (!inGraphScope(model.byId.get(id))) {
      enableExtensions();
    }
    if (model.byId.get(id).isGraph) {
      showRoot();
      return;
    }
    dismissStartHint();
    const visible = view.nodes.some(node => node.id === id);
    const focusedView = state.mode === 'explore' || state.mode === 'route';
    const newConnections = state.mode === 'overview' || focusedView && !visible;
    if (newConnections) {
      rememberView();
      state.mode = 'explore';
      beginNeighborhood(id);
    }
    state.selected = id;
    state.chain = state.chain && !newConnections;
    renderDetails();
    renderBindings();
    refreshView(newConnections);
    if (fromList && !newConnections) {
      revealSelection();
    }
    announce('Selected ' + model.byId.get(id).fullKey);
  }

  function selectPackage(pkg, members = [pkg]) {
    rememberView();
    state.packageKey = pkg;
    state.packageMembers = members;
    state.selected = null;
    state.mode = 'explore';
    beginNeighborhood(null, members);
    state.chain = false;
    state.limit = INITIAL_LIMIT;
    state.listLimit = LIST_PAGE;
    state.query = '';
    element('search').value = '';
    renderBindings();
    renderPackages();
    renderDetails();
    refreshView(true);
  }

  function renderPackages() {
    const container = element('package-list');
    container.replaceChildren();
    element('package-count').textContent = model.packages.size;
    const all = button('package-item', '', () => {
      state.packageKey = null;
      state.packageMembers = null;
      state.listLimit = LIST_PAGE;
      renderPackages();
      renderBindings();
      if (state.mode === 'explore' && state.exploration?.packages) {
        switchMode('overview');
      }
    });
    all.append(text('span', 'package-name', 'All packages'), text('span', 'package-count', model.bindingNodes.length));
    all.classList.toggle('active', state.packageKey === null);
    all.setAttribute('aria-pressed', String(state.packageKey === null));
    container.append(all);
    for (const [pkg, nodes] of Array.from(model.packages).sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]))) {
      const row = button('package-item', '', () => selectPackage(pkg));
      row.title = packageName(pkg);
      row.classList.toggle('active', Boolean(state.packageMembers?.includes(pkg)));
      row.setAttribute('aria-pressed', String(Boolean(state.packageMembers?.includes(pkg))));
      const dot = text('span', 'route-dot', '');
      dot.style.background = model.packageColors.get(pkg);
      row.append(dot, text('span', 'package-name', packageName(pkg)), text('span', 'package-count', nodes.length));
      container.append(row);
    }
  }

  function bindingCaption(node) {
    const name = (node.name || node.fullKey).toLocaleLowerCase();
    const declaration = [node.origin, node.declaration].find(value => value && value.toLocaleLowerCase() !== name && value !== node.fullKey);
    return [qualifierCaption(node.fullKey), declaration].filter(Boolean).join(' · ');
  }

  function initializeBindingGraphFilter() {
    const counts = new Map();
    for (const node of model.bindingNodes) {
      counts.set(node.regionId, (counts.get(node.regionId) || 0) + 1);
    }
    element('binding-graph-filter').hidden = counts.size < 2;
    const select = element('binding-graph');
    for (const region of model.regions) {
      const count = counts.get(region.id);
      if (!count) {
        continue;
      }
      const option = text('option', '', region.name + ' (' + count.toLocaleString() + ')');
      option.value = region.id;
      select.append(option);
    }
    select.addEventListener('change', event => {
      state.bindingRegion = event.target.value || null;
      state.listLimit = LIST_PAGE;
      state.listFocus = -1;
      renderBindings();
      element('binding-list').scrollTop = 0;
      announce(element('result-count').getAttribute('aria-label') + ' in ' + select.selectedOptions[0].textContent);
    });
  }

  function createBindingRow(node, index) {
    const row = button('binding-item', '', () => {
      if (state.selected === node.id) {
        clearSelection();
      } else {
        selectNode(node.id, true);
      }
    });
    const owner = model.regionById.get(node.regionId)?.name;
    const caption = bindingCaption(node);
    const kind = node.isGraphInput ? 'Graph input' : humanize(node.kind);
    row.title = [displayText(node.fullKey), owner && 'Owned by ' + owner, kind, node.scope, caption].filter(Boolean).join('\n');
    row.dataset.index = index;
    row.dataset.bindingId = node.id;
    row.classList.toggle('selected', node.id === state.selected);
    row.setAttribute('aria-current', node.id === state.selected ? 'true' : 'false');
    const dot = text('span', 'route-dot', '');
    dot.style.background = color(node);
    dot.setAttribute('aria-hidden', 'true');
    const label = text('span', 'binding-copy', '');
    label.append(text('span', 'binding-name', node.name || node.fullKey));
    const details = text('span', 'binding-description', '');
    if (owner && model.regions.length > 1 && !state.bindingRegion) {
      const graph = text('span', 'binding-graph', owner);
      graph.title = 'Owned by ' + owner;
      details.append(graph);
    }
    if (caption) {
      const detail = text('span', 'binding-package', caption);
      detail.title = caption;
      details.append(detail);
    }
    if (details.childElementCount) {
      label.append(details);
    }
    row.append(dot, label);
    if (state.sort !== 'name') {
      const metric = state.sort === 'centrality' ? (100 * Number(node.centrality || 0)).toFixed(1) + '%' : String(node[state.sort] || 0);
      const metricLabel = text('span', 'binding-meta', metric);
      metricLabel.title = element('sort-mode').selectedOptions[0].textContent;
      row.append(metricLabel);
    } else if (node.scoped || node.isGraphInput) {
      row.append(text('span', 'binding-meta', node.isGraphInput ? 'graph input' : 'scoped'));
    }
    return row;
  }

  function renderBindings() {
    const focusedId = document.activeElement?.dataset?.bindingId;
    const query = state.query.trim().toLocaleLowerCase();
    const tokens = query.split(/\s+/).filter(Boolean);
    listNodes = model.bindingNodes.filter(node => {
      if (state.bindingRegion && node.regionId !== state.bindingRegion) {
        return false;
      }
      if (state.packageMembers !== null && !state.packageMembers.includes(node.pkg || '')) {
        return false;
      }
      const haystack = [node.fullKey, node.name, node.kind, node.origin, node.declaration, node.scope, model.regionById.get(node.regionId)?.name].join(' ').toLocaleLowerCase();
      return tokens.every(token => haystack.includes(token));
    });
    listNodes.sort((a, b) => {
      if (state.sort !== 'name') {
        const difference = Number(b[state.sort] || 0) - Number(a[state.sort] || 0);
        if (difference) {
          return difference;
        }
      }
      return (a.name || a.fullKey).localeCompare(b.name || b.fullKey) || a.fullKey.localeCompare(b.fullKey);
    });
    element('search-clear').hidden = !state.query;
    element('browse-selection-clear').hidden = !state.selected;
    element('result-count').textContent = listNodes.length.toLocaleString();
    element('result-count').setAttribute('aria-label', listNodes.length.toLocaleString() + (listNodes.length === 1 ? ' binding' : ' bindings'));
    const container = element('binding-list');
    container.replaceChildren();
    const shown = listNodes.slice(0, state.listLimit);
    shown.forEach((node, index) => {
      const row = createBindingRow(node, index);
      container.append(row);
      if (node.id === focusedId) {
        row.focus();
      }
    });
    if (!shown.length) {
      container.append(text('p', 'empty-state', 'No bindings match. Try another search or change the graph or package filter.'));
    }
    if (listNodes.length > shown.length) {
      container.append(button('text-button list-more', 'Show more results (' + (listNodes.length - shown.length).toLocaleString() + ' remaining)', () => {
        state.listLimit += LIST_PAGE;
        renderBindings();
      }));
    }
    state.listFocus = Math.min(state.listFocus, shown.length - 1);
  }

  function detailSection(parent, title, count) {
    const section = text('section', 'detail-section', '');
    const heading = text('h3', 'section-heading', title);
    if (count !== undefined) {
      heading.append(text('span', 'section-count', count));
    }
    section.append(heading);
    parent.append(section);
    return section;
  }

  function detailRow(parent, label, value) {
    if (value === undefined || value === null || value === '') {
      return;
    }
    const row = text('div', 'detail-row', '');
    row.append(text('span', 'detail-label', label), text('span', 'detail-value', value));
    parent.append(row);
  }

  function bindingIdForKey(key, regionId) {
    return model.bindingNodes.find(node => node.regionId === regionId && node.fullKey === key)?.id;
  }

  function connection(parent, id, label, kind) {
    const node = model.byId.get(id);
    const row = node ? button('connection-item', '', () => selectNode(id, true)) : text('div', 'connection-item', '');
    row.title = displayText(label);
    let name = node?.isDefaultValue || node?.isRootMember ? node.name : displayKey(label);
    if (node?.isRootMember && model.regions.length > 1) {
      const graphName = model.regionById.get(node.regionId).name;
      name = node.isInheritedRoot ? name + ' · in ' + graphName : graphName + '.' + name;
    }
    row.append(text('span', 'connection-name', name));
    if (kind) {
      row.append(text('span', 'connection-kind', kind));
    }
    parent.append(row);
  }

  function renderDetails() {
    const container = element('details');
    const hadFocus = container.contains(document.activeElement);
    container.replaceChildren();
    element('selection-clear').hidden = state.selected === null;
    const node = model.byId.get(state.selected);
    const emptyTitle = state.packageKey === null ? 'Select a binding' : packageName(state.packageKey);
    const title = node ? node.name || shortName(node.id) : emptyTitle;
    const heading = text('h2', 'detail-heading', title);
    heading.tabIndex = -1;
    container.append(heading);
    if (hadFocus) {
      container.scrollTop = 0;
      heading.focus({ preventScroll: true });
    }
    if (!node) {
      container.append(text('p', 'empty-state', state.packageKey === null ? 'Select a binding to inspect its dependencies, consumers, and compiler decisions.' : 'Select a binding from this package to inspect its dependencies and consumers.'));
      const overview = detailSection(container, 'Graph summary');
      detailRow(overview, 'Graph', shortName(metroData.graphName));
      detailRow(overview, 'Bindings', model.bindingNodes.length.toLocaleString());
      detailRow(overview, 'Packages', model.packages.size.toLocaleString());
      detailRow(overview, 'Connections', model.links.length.toLocaleString());
      if (metroData.scopes?.length) {
        detailRow(overview, 'Scopes', metroData.scopes.join(', '));
      }
      const entries = detailSection(container, 'Roots', model.entryPoints.size);
      for (const id of model.entryPoints) {
        connection(entries, id, model.byId.get(id).name, model.byId.get(id).rootKind || 'root');
      }
      const regionGroups = [
        ['Graph extensions', model.regions.filter(region => region.parentId)],
        ['Graph dependencies', model.regions.filter(region => region.kind === 'dependency')],
      ];
      for (const [title, regions] of regionGroups) {
        if (!regions.length) {
          continue;
        }
        const section = detailSection(container, title, regions.length);
        for (const region of regions) {
          const row = button('connection-item', '', () => showRegion(region.id));
          const relationship = region.parentId ? 'extends ' + model.regionById.get(region.parentId).name : '@Includes';
          row.append(text('span', 'connection-name', region.name), text('span', 'connection-kind', relationship));
          section.append(row);
        }
      }
      appendGraphData(container);
      return;
    }
    const keyLabel = text('p', 'detail-key', displayKey(node.fullKey));
    keyLabel.title = displayText(node.fullKey);
    container.append(keyLabel);
    const tags = text('div', 'detail-tags', '');
    tags.append(text('span', 'tag', humanize(node.kind)));
    if (node.scoped) {
      tags.append(text('span', 'tag', 'Scoped'));
    }
    if (node.synthetic) {
      tags.append(text('span', 'tag', 'Synthetic'));
    }
    if (node.isIncludedGraph) {
      tags.append(text('span', 'tag root', '@Includes'));
    }
    if (node.parentBindingId) {
      tags.append(text('span', 'tag', 'Inherited binding'));
    }
    if (node.isGraph) {
      tags.append(text('span', 'tag', 'Graph root'));
    } else if (node.isGraphInput) {
      tags.append(text('span', 'tag root', 'Graph input'));
    } else if (node.isEntryPoint) {
      tags.append(text('span', 'tag root', node.isInheritedRoot ? 'Inherited root' : 'Root'));
    }
    container.append(tags);
    const actions = text('div', 'detail-actions', '');
    actions.append(button('text-button', 'Copy key', () => copyKey(node.fullKey)));
    const connectedRegion = model.regions.find(region => region.viaNodeId === node.id) || model.regionById.get(node.dependencyRegionId);
    if (connectedRegion) {
      actions.append(button('text-button', 'Show ' + connectedRegion.name, () => showRegion(connectedRegion.id)));
    }
    container.append(actions);
    if (!node.isGraphInput && !rootRoute(scopedGraph(), node.id)) {
      container.append(text('p', 'detail-note', unavailableRouteMessage()));
    }
    const facts = detailSection(container, node.isRootMember ? 'Graph root' : 'Binding');
    detailRow(facts, 'Owning graph', model.regionById.get(node.regionId)?.name);
    if (node.isRootMember) {
      detailRow(facts, 'Graph', shortName(node.rootOwner));
      detailRow(facts, 'Requested type', displayKey(node.requestedKey));
      if (node.isInheritedRoot) {
        const ancestor = model.regions.find(region => region.graphName === node.declaringGraph);
        detailRow(facts, 'Inherited from', ancestor?.name || shortName(node.declaringGraph));
        detailRow(facts, 'Declared in', node.declaringType);
        facts.append(text('p', 'detail-note', 'The ancestor accessor supplies this multibinding root request. Its contributions are resolved in this extension.'));
      }
      if (node.resolutionUnavailable) {
        facts.append(text('p', 'detail-note', 'No resolved dependency was recorded for this root.'));
      }
    }
    detailRow(facts, 'Package', packageName(node.pkg));
    detailRow(facts, 'Scope', node.scope);
    detailRow(facts, 'Declaration', node.declaration);
    detailRow(facts, 'Origin', node.origin);
    detailRow(facts, 'Alias target', node.aliasTarget);
    if (node.isGraphInput) {
      facts.append(text('p', 'detail-note', 'Supplied when this graph is created. Consumers receive the same instance.'));
    }
    if (node.isGraph && model.graphInputs.length) {
      const inputs = detailSection(container, 'Graph inputs', model.graphInputs.length);
      for (const input of model.graphInputs) {
        connection(inputs, input.id, input.fullKey, 'graph input');
      }
    }
    if (node.optionalWrapper) {
      detailRow(facts, 'Optional type', displayKey(node.optionalWrapper.wrappedType));
      detailRow(facts, 'Allows absent', node.optionalWrapper.allowsAbsent ? 'Yes' : 'No');
    }
    const metrics = detailSection(container, 'Analysis');
    metrics.hidden = node.isRootMember || node.kind === 'Graph';
    const grid = text('div', 'metric-grid', '');
    for (const [label, value] of [['Analysis fan-in', node.fanIn], ['Analysis fan-out', node.fanOut], ['Centrality', node.centrality === undefined ? undefined : (100 * node.centrality).toFixed(1) + '%'], ['Dominates', node.dominatorCount]]) {
      const metric = text('div', 'metric', '');
      metric.append(text('span', 'metric-value', value ?? '—'), text('span', 'metric-label', label));
      grid.append(metric);
    }
    metrics.append(grid, text('p', 'detail-note', 'Analysis uses the recorded binding graph. Roots, assisted targets, and default-value nodes can change the visible connection counts.'));
    const inspectorModel = state.mode === 'route' ? scopedGraph() : displayGraph();
    const outgoing = inspectorModel.outgoing.get(node.id) || [];
    const incoming = (inspectorModel.incoming.get(node.id) || []).filter(edge => !edge.rootMembership);
    const dependencies = detailSection(container, 'Dependencies', outgoing.length);
    for (const edge of outgoing) {
      const kind = edge.edgeType === 'includes' || edge.includes ? '@Includes' : edge.wrapperType || humanize(edge.edgeType);
      connection(dependencies, edge.target, model.byId.get(edge.target)?.fullKey || edge.target, kind + (edge.hasDefault ? ' · default available' : ''));
      collapsedDetails(dependencies, edge);
    }
    if (!outgoing.length) {
      dependencies.append(text('p', 'detail-note', 'No outgoing dependencies in the recorded graph.'));
    }
    const consumers = detailSection(container, 'Consumers', incoming.length);
    for (const edge of incoming) {
      connection(consumers, edge.source, model.byId.get(edge.source)?.fullKey || edge.source, edge.edgeType === 'includes' || edge.includes ? '@Includes' : humanize(edge.edgeType));
      collapsedDetails(consumers, edge);
    }
    if (node.rawDependencies?.length) {
      const raw = document.createElement('details');
      raw.className = 'detail-section';
      raw.append(text('summary', 'section-heading', 'Declared dependency keys · ' + node.rawDependencies.length));
      for (const dependency of node.rawDependencies) {
        connection(raw, bindingIdForKey(dependency.key, node.regionId), dependency.key, [dependency.wrapperType, dependency.hasDefault ? 'default available' : ''].filter(Boolean).join(' · '));
      }
      container.append(raw);
    }
    if (node.assistedParams?.length) {
      const assisted = detailSection(container, 'Assisted parameters', node.assistedParams.length);
      for (const parameter of node.assistedParams) {
        detailRow(assisted, parameter.name, displayKey(parameter.fullKey || parameter.key || parameter.type));
      }
    }
    if (node.multibinding) {
      const multibinding = detailSection(container, 'Multibinding');
      detailRow(multibinding, 'Collection', node.multibinding.type);
      detailRow(multibinding, 'Allows empty', node.multibinding.allowEmpty ? 'Yes' : 'No');
      for (const key of node.multibinding.sources || []) {
        connection(multibinding, bindingIdForKey(key, node.regionId), key, 'contribution');
      }
    }
    renderExplanations(container, node);
  }

  function collapsedDetails(parent, edge) {
    if (!edge.collapsedVia?.length) {
      return;
    }
    const disclosure = text('details', 'collapsed-path', '');
    disclosure.append(text('summary', '', 'Via ' + edge.collapsedVia.length + ' hidden binding' + (edge.collapsedVia.length === 1 ? '' : 's')));
    for (const id of edge.collapsedVia) {
      const node = model.byId.get(id);
      connection(disclosure, id, displayText(node.fullKey), humanize(node.kind));
    }
    parent.append(disclosure);
  }

  function appendGraphData(parent) {
    for (const [title, value] of [['Compiler counters', metroData.stats], ['Compiler configuration', metroData.config]]) {
      if (!value || !Object.keys(value).length) {
        continue;
      }
      const disclosure = document.createElement('details');
      disclosure.className = 'detail-section';
      disclosure.append(text('summary', 'section-heading', title), text('pre', 'detail-code', JSON.stringify(value, null, 2)));
      parent.append(disclosure);
    }
  }

  function renderExplanations(parent, node) {
    const records = model.explanations.get(node.id) || [];
    const section = detailSection(parent, 'Compiler decisions', records.length);
    if (!records.length) {
      section.append(text('p', 'detail-note', 'No compiler decisions were recorded for this key.'));
      return;
    }
    for (const record of records) {
      const disclosure = document.createElement('details');
      disclosure.className = 'explanation-item';
      const candidates = record.candidates || [];
      const matching = candidates.filter(candidate => candidate.key === node.fullKey);
      disclosure.append(text('summary', '', humanize(record.outcome) + ' · ' + humanize(record.phase)));
      detailRow(disclosure, 'Context', record.context?.label);
      detailRow(disclosure, 'Request', displayKey(record.request?.key));
      for (const candidate of matching.length ? matching : candidates) {
        const candidateBlock = text('div', 'explanation-candidate', '');
        detailRow(candidateBlock, humanize(candidate.status), displayKey(candidate.key));
        detailRow(candidateBlock, 'Reason', typeof candidate.reason === 'string' ? humanize(candidate.reason) : JSON.stringify(candidate.reason));
        detailRow(candidateBlock, 'Declaration', candidate.declaration?.label);
        const source = candidate.declaration?.source;
        if (source) {
          detailRow(candidateBlock, 'Source', source.path + (source.line ? ':' + source.line : '') + (source.column ? ':' + source.column : ''));
        }
        for (const related of candidate.relatedDeclarations || []) {
          detailRow(candidateBlock, 'Related', related.label);
        }
        for (const detail of candidate.details || []) {
          candidateBlock.append(text('p', 'detail-note', detail));
        }
        disclosure.append(candidateBlock);
      }
      for (const detail of record.details || []) {
        disclosure.append(text('p', 'detail-note', detail));
      }
      section.append(disclosure);
    }
  }

  async function copyKey(key) {
    try {
      await navigator.clipboard.writeText(key);
      toast('Binding key copied');
    } catch (_) {
      const area = document.createElement('textarea');
      area.value = key;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.append(area);
      area.select();
      const copied = document.execCommand('copy');
      area.remove();
      toast(copied ? 'Binding key copied' : 'Select the full key in the inspector to copy it.');
    }
  }

  function invalidate() {
    dirty = true;
    if (frame === null) {
      frame = requestAnimationFrame(paint);
    }
  }

  function toScreen(point, position = camera) {
    return { x: (point.x - position.x) * position.scale + width / 2, y: (point.y - position.y) * position.scale + height / 2 };
  }

  function openView() {
    if (view.kind === 'route') {
      focusRoute();
      return;
    }
    if (view.kind === 'full') {
      const boundary = scene.boundary;
      if (!boundary) {
        fitView();
        return;
      }
      const root = { x: boundary.labelX, y: boundary.labelY };
      const viewport = mapViewport();
      const rootX = viewport.left + 10;
      const top = viewport.top + 40;
      const y = root.y + (height / 2 - top) / 0.85;
      moveCamera({ x: root.x + (width / 2 - rootX) / 0.85, y, scale: 0.85 });
      return;
    }
    if (view.kind === 'explore' && state.exploration?.anchor === model.root && !state.selected) {
      focusRoots();
      return;
    }
    const focusBinding = view.kind === 'explore' && !state.exploration?.packages;
    fitView(focusBinding ? 0.55 : 0);
  }

  function mapViewport() {
    const bounds = canvas.getBoundingClientRect();
    let top = 24;
    let bottom = height - 24;
    const overlays = document.querySelectorAll('.map-topbar, .graph-navigation, #selection-toolbar, #explorer-controls, #chain-controls');
    for (const overlay of overlays) {
      if (overlay.hidden) {
        continue;
      }
      const rect = overlay.getBoundingClientRect();
      const overlapsCanvas = rect.right > bounds.left && rect.left < bounds.right && rect.bottom > bounds.top && rect.top < bounds.bottom;
      if (rect.width > 0 && rect.height > 0 && overlapsCanvas) {
        top = Math.max(top, rect.bottom - bounds.top + 16);
      }
    }
    const controls = element('expand-map').closest('.map-controls');
    if (controls) {
      const rect = controls.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0 && rect.top < bounds.bottom && rect.bottom > bounds.top) {
        bottom = Math.min(bottom, rect.top - bounds.top - 16);
      }
    }
    top = Math.min(top, Math.max(24, height - 104));
    bottom = Math.max(top + 80, bottom);
    return { left: 24, right: Math.max(104, width - 24), top, bottom };
  }

  function focusRoute() {
    if (!view.nodes.length) {
      fitView();
      return;
    }
    const scale = 0.85;
    const direction = flowDirection('route');
    const first = view.nodes[0];
    const viewport = mapViewport();
    const x = direction === 'rl' ? Math.max(viewport.left + 52, viewport.right - 300) : viewport.left + 52;
    const y = direction === 'bt' ? viewport.bottom - 20 : viewport.top + 66;
    moveCamera({ x: first.x + (width / 2 - x) / scale, y: first.y + (height / 2 - y) / scale, scale });
  }

  function focusRoots() {
    const owner = state.exploration?.anchor || model.root;
    const roots = view.nodes.filter(node => node.isRootMember && node.rootOwner === owner);
    if (!roots.length) {
      fitView();
      return;
    }
    const viewport = mapViewport();
    const labelsOnLeft = flowDirection() === 'lr';
    const labelPadding = Math.min(260, (viewport.right - viewport.left) / 3);
    const left = viewport.left + (labelsOnLeft ? labelPadding : 24);
    const right = viewport.right - (labelsOnLeft ? 24 : labelPadding);
    const top = viewport.top + 24;
    const bottom = Math.max(top + 40, viewport.bottom - 40);
    const minX = Math.min(...roots.flatMap(node => [node.x, node.routeX]));
    const maxX = Math.max(...roots.flatMap(node => [node.x, node.routeX]));
    const minY = Math.min(...roots.flatMap(node => [node.y, node.routeY]));
    const maxY = Math.max(...roots.flatMap(node => [node.y, node.routeY]));
    const scale = Math.min(0.85, Math.max(40, right - left) / Math.max(1, maxX - minX), (bottom - top) / Math.max(1, maxY - minY));
    moveCamera({
      x: (minX + maxX) / 2 + (width / 2 - (left + right) / 2) / scale,
      y: (minY + maxY) / 2 + (height / 2 - (top + bottom) / 2) / scale,
      scale,
    });
  }

  function viewFitBounds(boundaries = [scene.boundary, ...scene.regionFrames].filter(Boolean)) {
    const minX = Math.min(...boundaries.map(boundary => boundary.left));
    const right = Math.max(...boundaries.map(boundary => boundary.right));
    const top = Math.min(...boundaries.map(boundary => boundary.top));
    const maxY = Math.max(...boundaries.map(boundary => boundary.bottom));
    const viewport = mapViewport();
    const topPadding = viewport.top;
    const bottomPadding = height - viewport.bottom;
    const availableWidth = Math.max(80, viewport.right - viewport.left);
    const availableHeight = Math.max(80, viewport.bottom - viewport.top);
    context.save();
    context.font = '600 14px -apple-system, BlinkMacSystemFont, sans-serif';
    const titles = boundaries.filter(boundary => boundary.showTitle !== false).map(boundary => ({
      boundary,
      width: context.measureText(model.regionById.get(boundary.id)?.name || shortName(model.root || metroData.graphName)).width + 18,
    }));
    context.restore();
    let maxX = right;
    let minY = top;
    let scale = Math.min(1, availableWidth / Math.max(80, maxX - minX), availableHeight / Math.max(80, maxY - minY));
    // Graph titles keep their text size as the map zooms.
    for (let pass = 0; pass < 12; pass++) {
      maxX = Math.max(right, ...titles.map(title => title.boundary.labelX + title.width / scale));
      minY = Math.min(top, ...titles.map(title => title.boundary.labelY - 40 / scale));
      scale = Math.min(1, availableWidth / Math.max(80, maxX - minX), availableHeight / Math.max(80, maxY - minY));
    }
    return { minX, maxX, minY, maxY, topPadding, bottomPadding, availableHeight, scale };
  }

  function fitView(minimumScale = 0, boundaries) {
    if (typeof minimumScale !== 'number') {
      minimumScale = 0;
    }
    if (!view.nodes.length) {
      moveCamera({ x: 0, y: 0, scale: 1 });
      return;
    }
    const bounds = viewFitBounds(boundaries);
    const { minX, maxX, minY, maxY, topPadding, bottomPadding, availableHeight } = bounds;
    const scale = Math.max(minimumScale, bounds.scale);
    const centerY = topPadding + availableHeight / 2;
    const target = {
      x: (minX + maxX) / 2,
      y: (minY + maxY) / 2 - (centerY - height / 2) / scale,
      scale,
    };
    if (minimumScale && state.selected) {
      const selected = view.nodes.find(node => node.id === state.selected);
      if (selected) {
        const point = toScreen(selected, target);
        if (point.x < 30 || point.x > width - 100 || point.y < topPadding || point.y > height - bottomPadding) {
          target.x = selected.x + 70;
          target.y = selected.y - (centerY - height / 2) / scale;
        }
      }
    }
    moveCamera(target);
  }

  function showRoot(entryPointsOnly = false) {
    if (!model.root) {
      return;
    }
    const showingRoots = state.mode === 'explore' && state.exploration?.anchor === model.root;
    if (entryPointsOnly && showingRoots) {
      resize();
      focusRoots();
      return;
    }
    if (entryPointsOnly) {
      rememberView();
    }
    state.mode = entryPointsOnly ? 'explore' : 'full';
    state.selected = null;
    state.chain = false;
    state.packageKey = null;
    state.packageMembers = null;
    state.direction = 'dependencies';
    state.depth = 1;
    state.limit = INITIAL_LIMIT;
    beginNeighborhood(model.root);
    element('direction').value = state.direction;
    element('depth').value = String(state.depth);
    renderPackages();
    renderBindings();
    renderDetails();
    refreshView();
    resize();
    openView();
    announce(entryPointsOnly ? 'Showing graph roots' : 'Showing ' + shortName(model.root) + '. Follow dependencies from its roots.');
  }

  function showRegion(id) {
    if (!model.regionById.has(id)) {
      showRoot();
      return;
    }
    if (extensionRegions.has(id)) {
      enableExtensions();
    }
    state.mode = 'full';
    state.selected = null;
    state.chain = false;
    renderDetails();
    renderBindings();
    refreshView();
    resize();
    const boundary = [scene.boundary, ...scene.regionFrames].find(region => region?.id === id);
    fitView(0, boundary ? [boundary] : undefined);
    announce('Showing ' + model.regionById.get(id).name);
  }

  function revealSelection() {
    resize();
    const selected = view.nodes.find(node => node.id === state.selected);
    if (!selected) {
      return;
    }
    const point = toScreen(selected);
    const viewport = mapViewport();
    if (point.x < viewport.left || point.x > viewport.right || point.y < viewport.top || point.y > viewport.bottom) {
      const centerX = (viewport.left + viewport.right) / 2;
      const centerY = (viewport.top + viewport.bottom) / 2;
      moveCamera({ ...camera, x: selected.x + (width / 2 - centerX) / camera.scale, y: selected.y + (height / 2 - centerY) / camera.scale });
    }
  }

  function zoom(factor, anchor = { x: width / 2, y: height / 2 }) {
    dismissStartHint();
    stopCamera();
    const previousScale = camera.scale;
    const fittedMinimum = scene.boundary ? Math.min(0.025, viewFitBounds().scale / 2) : 0.025;
    const minimumScale = Math.min(previousScale, fittedMinimum);
    camera.scale = Math.max(minimumScale, Math.min(4, camera.scale * factor));
    camera.x += (anchor.x - width / 2) * (1 / previousScale - 1 / camera.scale);
    camera.y += (anchor.y - height / 2) * (1 / previousScale - 1 / camera.scale);
    invalidate();
  }

  function strokeRoute(points) {
    context.beginPath();
    context.moveTo(points[0].x, points[0].y);
    for (let index = 1; index < points.length - 1; index++) {
      const next = points[index + 1];
      context.arcTo(points[index].x, points[index].y, next.x, next.y, 7 * Math.min(1, camera.scale));
    }
    context.lineTo(points[points.length - 1].x, points[points.length - 1].y);
    context.stroke();
  }

  function pathDistance(points) {
    let distance = 0;
    for (let index = 1; index < points.length; index++) {
      distance += Math.hypot(points[index].x - points[index - 1].x, points[index].y - points[index - 1].y);
    }
    return distance;
  }

  function pointAlong(points, distance) {
    for (let index = 1; index < points.length; index++) {
      const start = points[index - 1];
      const end = points[index];
      const length = Math.hypot(end.x - start.x, end.y - start.y);
      if (distance <= length && length > 0) {
        const t = distance / length;
        return { x: start.x + (end.x - start.x) * t, y: start.y + (end.y - start.y) * t };
      }
      distance -= length;
    }
    return points[points.length - 1];
  }

  function trimLabel(value, maxWidth) {
    let label = String(value || '');
    if (context.measureText(label).width <= maxWidth) {
      return label;
    }
    while (label.length > 1 && context.measureText(label + '…').width > maxWidth) {
      label = label.slice(0, -1);
    }
    return label + '…';
  }

  function wrapLabel(value, maxWidth, maximumLines) {
    const lines = [];
    let remaining = String(value);
    while (lines.length < maximumLines - 1 && context.measureText(remaining).width > maxWidth) {
      let length = trimLabel(remaining, maxWidth).length - 1;
      const separator = Math.max(remaining.lastIndexOf('.', length), remaining.lastIndexOf(' ', length));
      if (separator > length / 2) {
        length = separator + 1;
      }
      lines.push(remaining.slice(0, length).trim());
      remaining = remaining.slice(length).trim();
    }
    lines.push(trimLabel(remaining, maxWidth));
    return lines;
  }

  function traceContour(points, radius = 0) {
    if (points.length < 3) {
      return;
    }
    const first = points[0];
    const last = points.at(-1);
    context.moveTo((last.x + first.x) / 2, (last.y + first.y) / 2);
    for (let index = 0; index < points.length; index++) {
      const point = points[index];
      const next = points[(index + 1) % points.length];
      const previous = points[(index + points.length - 1) % points.length];
      const corner = Math.min(radius, Math.hypot(point.x - previous.x, point.y - previous.y) / 3, Math.hypot(next.x - point.x, next.y - point.y) / 3);
      context.arcTo(point.x, point.y, (point.x + next.x) / 2, (point.y + next.y) / 2, corner);
    }
    context.closePath();
  }

  function clipPreviousRegions(regions, padding = 0) {
    for (const region of regions) {
      if (region.opacity * region.enclosureOpacity < 0.001) {
        continue;
      }
      context.beginPath();
      context.rect(0, 0, width, height);
      traceContour(offsetContour(region.contour.map(point => toScreen(point)), padding), 16 * camera.scale + padding);
      context.clip('evenodd');
    }
  }

  function paintRegionGrid(boundary, previousRegions) {
    const inset = offsetContour(boundary.contour.map(point => toScreen(point)), -12);
    if (inset.length < 3) {
      return;
    }
    context.save();
    context.beginPath();
    traceContour(inset, Math.max(0, 16 * camera.scale - 12));
    context.clip();
    clipPreviousRegions(previousRegions, 12);
    const grid = 34;
    const offsetX = ((width / 2 - camera.x * camera.scale) % grid + grid) % grid;
    const offsetY = ((height / 2 - camera.y * camera.scale) % grid + grid) % grid;
    context.fillStyle = '#34475b';
    context.globalAlpha = boundary.opacity * 0.36;
    for (let x = offsetX; x < width; x += grid) {
      for (let y = offsetY; y < height; y += grid) {
        context.fillRect(x, y, 1, 1);
      }
    }
    context.restore();
  }

  function paintBoundary(boundary, name = model.regions[0]?.name || shortName(model.root || metroData.graphName), previousRegions = []) {
    if (!boundary || boundary.opacity < 0.001) {
      return null;
    }
    const enclosureOpacity = boundary.opacity * boundary.enclosureOpacity;
    if (enclosureOpacity > 0.001) {
      const points = boundary.contour.map(point => toScreen(point));
      context.save();
      clipPreviousRegions(previousRegions);
      context.beginPath();
      traceContour(points, 16 * camera.scale);
      context.fillStyle = '#96abc0';
      context.globalAlpha = enclosureOpacity * 0.03;
      context.fill();
      context.restore();
      paintRegionGrid({ ...boundary, opacity: enclosureOpacity }, previousRegions);
      context.beginPath();
      traceContour(points, 16 * camera.scale);
      context.strokeStyle = '#0b1017';
      context.lineWidth = 3.5;
      context.globalAlpha = enclosureOpacity * 0.9;
      context.stroke();
      context.strokeStyle = '#8998a9';
      context.lineWidth = 1.2;
      context.globalAlpha = enclosureOpacity * 0.65;
      context.stroke();
    }
    context.globalAlpha = 1;
    if (boundary.showTitle === false) {
      return null;
    }
    const label = toScreen({ x: boundary.labelX, y: boundary.labelY });
    label.x += 8;
    label.y -= 22;
    context.font = '600 14px -apple-system, BlinkMacSystemFont, sans-serif';
    return { x: label.x - 6, y: label.y - 18, width: context.measureText(name).width + 12, height: 26, name, opacity: boundary.opacity, regionId: boundary.id, exiting: boundary.exiting };
  }

  function paint(timestamp) {
    frame = null;
    const transitioning = Boolean(graphTransition || cameraTransition);
    if (!motionEnabled()) {
      finishTransitions();
    }
    sampleCamera(timestamp);
    const drawing = sampleScene(timestamp);
    if (transitioning) {
      dirty = true;
    }
    const moving = Boolean(graphTransition || cameraTransition);
    const animate = motionEnabled() && view.edges.length > 0;
    if (!dirty && (!animate || timestamp - lastPaint < 32)) {
      if (animate) {
        frame = requestAnimationFrame(paint);
      }
      return;
    }
    if (!dirty) {
      lastPaint = timestamp;
      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
      context.drawImage(staticCanvas, 0, 0, width, height);
      paintTraffic(timestamp);
      frame = requestAnimationFrame(paint);
      return;
    }
    dirty = false;
    lastPaint = timestamp;
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    context.fillStyle = '#0b1017';
    context.fillRect(0, 0, width, height);
    const regions = [drawing.boundary, ...drawing.regionFrames].filter(Boolean);
    const boundaryLabels = regions.map((region, index) => paintBoundary(region, model.regionById.get(region.id)?.name, regions.slice(0, index))).filter(Boolean);
    const regionHit = pointerPosition && !drag ? hitRegionName(canvasPoint(pointerPosition), boundaryLabels) : null;
    state.hoveredRegion = regionHit?.regionId || null;
    if (regionHit) {
      state.hovered = null;
      element('canvas-tooltip').hidden = true;
    }
    if (drawing.radialOpacity > 0) {
      context.save();
      const inset = offsetContour(drawing.boundary.contour.map(point => toScreen(point)), -12);
      context.beginPath();
      traceContour(inset, Math.max(0, 16 * camera.scale - 12));
      context.clip();
      const center = toScreen(orientPoint(radialPositions.get(model.root) || { x: 0, y: 0 }, 'radial', flowDirection('radial')));
      const radii = new Set(model.nodes.filter(node => !node.isGraph && !node.isGraphInput).map(node => radialPositions.get(node.id)).filter(Boolean).map(point => Math.round(Math.hypot(point.x, point.y))));
      context.strokeStyle = '#546476';
      context.lineWidth = 1;
      context.globalAlpha = 0.18 * drawing.radialOpacity;
      context.setLineDash([2, 8]);
      for (const radius of radii) {
        context.beginPath();
        context.arc(center.x, center.y, radius * camera.scale, 0, Math.PI * 2);
        context.stroke();
      }
      context.setLineDash([]);
      context.restore();
    }
    const drawnNodes = drawing.nodes.map(node => {
      const anchor = toScreen({ x: node.routeX, y: node.routeY });
      return { ...node, ...toScreen(node), routeX: anchor.x, routeY: anchor.y };
    });
    screenNodes = drawnNodes.filter(node => !node.isGraph && !node.exiting && node.opacity > 0.15);
    const byId = new Map(drawnNodes.map(node => [node.id, node]));
    const { attention, related, activeEdges } = attentionFor(view, state.hovered || state.selected);
    const hovering = Boolean(state.hovered);
    trafficPaths = [];
    const crowded = view.edges.length > 450;
    drawing.edges.forEach(edge => {
      const index = edge.renderIndex;
      const source = byId.get(edge.target);
      const target = byId.get(edge.source);
      if (!source || !target) {
        return;
      }
      const xs = [source.x, target.x, source.routeX, target.routeX];
      const ys = [source.y, target.y, source.routeY, target.routeY];
      if (Math.max(...xs) < -60 || Math.min(...xs) > width + 60 || Math.max(...ys) < -60 || Math.min(...ys) > height + 60) {
        return;
      }
      const active = hovering ? !attention || activeEdges.has(edge.id) : edge.emphasis > 0.1;
      const points = connectionRoutePoints(source, target, index, edge.renderKind);
      context.strokeStyle = edge.includes || edge.edgeType === 'includes' ? '#70b5df' : edge.lineStyle?.color || color(source);
      context.lineWidth = edge.renderKind === 'overview' ? Math.min(4, 1.3 + Math.log2(1 + edge.count) * 0.45) : active && attention ? 2.2 : crowded ? 0.8 : 1.45;
      const emphasis = hovering ? active ? 0.88 : 0.07 : edge.emphasis;
      context.globalAlpha = edge.opacity * emphasis;
      if (context.globalAlpha < 0.001) {
        return;
      }
      context.lineJoin = 'round';
      context.lineCap = 'round';
      context.setLineDash(edge.edgeType === 'deferrable' || edge.lineStyle?.type === 'dashed' ? [5, 5] : edge.lineStyle?.type === 'dotted' ? [1, 5] : []);
      strokeRoute(points);
      context.setLineDash([]);
      if (edge.renderKind !== 'overview' && active && camera.scale > 0.22) {
        const end = points[points.length - 1];
        const before = [...points.slice(0, -1)].reverse().find(point => Math.hypot(end.x - point.x, end.y - point.y) > 0.1) || points[0];
        const angle = Math.atan2(end.y - before.y, end.x - before.x);
        const tip = { x: end.x - Math.cos(angle) * 10, y: end.y - Math.sin(angle) * 10 };
        context.fillStyle = context.strokeStyle;
        context.beginPath();
        context.moveTo(tip.x, tip.y);
        context.lineTo(tip.x - Math.cos(angle - 0.48) * 6, tip.y - Math.sin(angle - 0.48) * 6);
        context.lineTo(tip.x - Math.cos(angle + 0.48) * 6, tip.y - Math.sin(angle + 0.48) * 6);
        context.closePath();
        context.fill();
      }
      if (animate && active && trafficPaths.length < 32 && (attention || view.kind === 'route' || index % Math.max(1, Math.floor(view.edges.length / 14)) === 0)) {
        trafficPaths.push({ points, length: pathDistance(points), color: context.strokeStyle, index, opacity: edge.opacity });
      }
    });
    context.globalAlpha = 1;
    const highlightedBoundary = regions.find(region => region.id === state.hoveredRegion);
    if (highlightedBoundary) {
      context.save();
      context.beginPath();
      traceContour(highlightedBoundary.contour.map(point => toScreen(point)), 16 * camera.scale);
      context.strokeStyle = '#a2e6cd';
      context.lineWidth = 8;
      context.globalAlpha = highlightedBoundary.opacity * 0.12;
      context.stroke();
      context.lineWidth = 2.4;
      context.globalAlpha = highlightedBoundary.opacity;
      context.stroke();
      context.restore();
    }
    labelRects = [];
    const occupied = new Map();
    const cellsFor = rect => {
      const cells = [];
      for (let x = Math.floor(rect.x / 48); x <= Math.floor((rect.x + rect.width) / 48); x++) {
        for (let y = Math.floor(rect.y / 24); y <= Math.floor((rect.y + rect.height) / 24); y++) {
          cells.push(x + ':' + y);
        }
      }
      return cells;
    };
    const reserve = rect => {
      for (const cell of cellsFor(rect)) {
        if (!occupied.has(cell)) {
          occupied.set(cell, []);
        }
        occupied.get(cell).push(rect);
      }
    };
    const overlaps = rect => cellsFor(rect).some(cell => (occupied.get(cell) || []).some(other =>
      rect.x < other.x + other.width && rect.x + rect.width > other.x && rect.y < other.y + other.height && rect.y + rect.height > other.y));
    for (const label of boundaryLabels) {
      context.globalAlpha = label.opacity;
      context.fillStyle = '#0b1017';
      context.fillRect(label.x, label.y, label.width, label.height);
      context.font = '600 14px -apple-system, BlinkMacSystemFont, sans-serif';
      context.textAlign = 'left';
      context.fillStyle = label.regionId === state.hoveredRegion ? '#a2e6cd' : '#bdcbd9';
      context.fillText(label.name, label.x + 6, label.y + 18);
      context.globalAlpha = 1;
      reserve(label);
      labelRects.push(label);
    }
    const onScreen = (x, y) => x >= -280 && x <= width + 30 && y >= -30 && y <= height + 30;
    const visibleNodes = drawnNodes.filter(node => node.opacity > 0.001 && (onScreen(node.x, node.y) || onScreen(node.routeX, node.routeY)));
    if (state.startHint && !hintTimer && visibleNodes.some(node => node.isRootMember)) {
      hintTimer = setTimeout(dismissStartHint, 4000);
    }
    const glyphScale = Math.min(1, camera.scale / 0.65);
    const neighbors = state.mode === 'explore' ? displayGraph() : null;
    for (const node of visibleNodes) {
      const selected = node.id === state.selected;
      const hovered = node.id === state.hovered;
      const active = hovering ? !attention || related.has(node.id) : node.emphasis > 0.5;
      const opacity = node.opacity * (hovering ? active ? 1 : 0.28 : node.emphasis);
      const boundaryPoint = node.isGraphInput || node.isRootMember;
      const radius = node.group ? 14 : selected || hovered ? 7 : boundaryPoint ? Math.max(3, 6 * glyphScale) : Math.max(node.renderKind === 'circular' ? 1.3 : 0.65, (node.scoped ? 5.5 : 4) * glyphScale);
      node.radius = radius;
      reserve({ x: node.x - radius - 3, y: node.y - radius - 3, width: radius * 2 + 6, height: radius * 2 + 6 });
      context.globalAlpha = opacity;
      const nodeColor = node.group ? node.color : node.isIncludedGraph ? '#70b5df' : node.isGraphInput ? '#71c8a0' : color(node);
      if (boundaryPoint && node.boundaryWeight > 0 && camera.scale >= 0.3) {
        context.globalAlpha = opacity * node.boundaryWeight;
        const dx = node.flowX;
        const dy = node.flowY;
        const input = node.isGraphInput;
        const from = input ? -24 : radius + 3;
        const to = input ? -radius - 3 : 24;
        const endX = node.x + dx * to;
        const endY = node.y + dy * to;
        context.strokeStyle = nodeColor;
        context.lineWidth = 2;
        context.beginPath();
        context.moveTo(node.x + dx * from, node.y + dy * from);
        context.lineTo(endX, endY);
        context.stroke();
        context.fillStyle = nodeColor;
        context.beginPath();
        context.moveTo(endX, endY);
        context.lineTo(endX - dx * 5 + dy * 3, endY - dy * 5 - dx * 3);
        context.lineTo(endX - dx * 5 - dy * 3, endY - dy * 5 + dx * 3);
        context.closePath();
        context.fill();
        context.globalAlpha = opacity;
      }
      if (selected || hovered || node.isEntryPoint && camera.scale > 0.4) {
        context.strokeStyle = node.isEntryPoint ? '#71c8a0' : nodeColor;
        context.lineWidth = 1;
        context.globalAlpha = opacity * 0.5;
        context.beginPath();
        context.arc(node.x, node.y, radius + (selected || hovered ? 7 : 4), 0, Math.PI * 2);
        context.stroke();
        context.globalAlpha = opacity;
      }
      const packageMember = node.renderKind === 'explore' && state.packageKey !== null && !state.selected && !node.contextNode;
      context.fillStyle = node.group || selected || packageMember || node.isGraphInput ? nodeColor : '#0b1017';
      context.strokeStyle = node.scoped ? '#e7eef6' : nodeColor;
      context.lineWidth = node.group ? 2 : Math.max(0.65, 2 * glyphScale);
      context.beginPath();
      if (node.isGraphInput) {
        context.rect(node.x - radius, node.y - radius, radius * 2, radius * 2);
      } else {
        context.arc(node.x, node.y, radius, 0, Math.PI * 2);
      }
      context.fill();
      context.stroke();
      if (neighbors && !node.isGraph && camera.scale >= 0.4) {
        const connections = node.isGraphInput ? neighbors.incoming.get(node.id) : neighbors.outgoing.get(node.id);
        if (connections?.length) {
          const expanded = state.exploration?.expanded.has(node.id);
          const x = node.x;
          const y = node.y + radius + 10;
          context.strokeStyle = nodeColor;
          context.lineWidth = 1;
          context.beginPath();
          context.moveTo(x - 3, y);
          context.lineTo(x + 3, y);
          if (!expanded) {
            context.moveTo(x, y - 3);
            context.lineTo(x, y + 3);
          }
          context.stroke();
        }
      }
      if (node.group) {
        context.fillStyle = '#fff';
        context.font = '600 11px -apple-system, BlinkMacSystemFont, sans-serif';
        context.textAlign = 'center';
        context.fillText(String(node.count), node.x, node.y + 4);
      }
      if (node.routeIndex !== undefined) {
        context.fillStyle = '#748397';
        context.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
        context.textAlign = 'right';
        context.fillText(String(node.routeIndex).padStart(2, '0'), node.x - 18, node.y + 4);
      }
    }
    const ordered = [...visibleNodes].sort((a, b) => Number(b.id === state.selected || b.id === state.hovered) - Number(a.id === state.selected || a.id === state.hovered) || Number(b.id === attention) - Number(a.id === attention) || Number(b.chainNode) - Number(a.chainNode) || Number(b.isGraph) - Number(a.isGraph) || Number(b.isEntryPoint) - Number(a.isEntryPoint) || (b.fanIn || 0) - (a.fanIn || 0));
    for (const node of ordered) {
      const selected = node.id === state.selected;
      const hovered = node.id === state.hovered;
      const active = hovering ? !attention || related.has(node.id) : node.emphasis > 0.5;
      const opacity = node.opacity * (hovering ? active ? 1 : 0.28 : node.emphasis);
      const alwaysLabel = node.group || node.renderKind === 'route' || selected || hovered || node.isGraph || node.chainNode && camera.scale >= 0.4;
      const zoomOpacity = Math.max(0, Math.min(1, (camera.scale - 0.32) / 0.08));
      const emphasisOpacity = hovering ? Number(active) : Math.max(0, Math.min(1, (node.emphasis - 0.28) / 0.72));
      const labelOpacity = alwaysLabel ? 1 : zoomOpacity * emphasisOpacity;
      if (labelOpacity < 0.01) {
        continue;
      }
      context.font = (selected || node.group || node.isGraph ? '600 ' : '400 ') + '12px -apple-system, BlinkMacSystemFont, sans-serif';
      const labelOnLeft = node.labelSide === 'left' && node.renderKind !== 'route';
      const horizontalSpacing = node.flowY && node.renderKind !== 'radial' && node.renderKind !== 'circular' ? ROW_GAP * 2.5 : COLUMN_GAP;
      const routeWidth = node.labelBelow ? Math.max(60, ROUTE_GAP * 3 * camera.scale - 40) : width;
      const spacingWidth = node.group ? Math.max(90, Math.min(240, 420 * camera.scale - 50)) : node.renderKind === 'route' ? routeWidth : Math.max(120, Math.min(270, horizontalSpacing * camera.scale - 48));
      const labelX = node.routeX;
      const labelY = node.routeY;
      const availableWidth = labelOnLeft ? labelX - 24 : width - labelX - node.radius - 24;
      const maxWidth = Math.max(40, Math.min(spacingWidth, availableWidth));
      const label = displayText(node.group ? packageName(node.pkg) : node.name || shortName(node.id));
      const lines = wrapLabel(label, maxWidth, node.group ? 2 : 1);
      const caption = node.group || node.isGraph ? '' : node.caption || (node.contextNode ? packageName(node.pkg) : '');
      let entryLabel = '';
      if (node.isRootMember) {
        entryLabel = node.isInheritedRoot ? 'INHERITED ROOT' : node.rootKind.toUpperCase();
      } else if (node.isEntryPoint && !(node.renderKind === 'route' && caption)) {
        entryLabel = 'ROOT';
      }
      let secondary = entryLabel;
      if (node.group) {
        secondary = node.packages.length > 1 ? node.packages.length + ' packages' : 'Explore package';
      } else if (node.isGraphInput) {
        secondary = node.isIncludedGraph ? 'GRAPH INPUT · @Includes' : 'GRAPH INPUT';
      } else if (node.isGraphInstance) {
        secondary = 'GRAPH INSTANCE';
      } else if (node.isIncludedGraph) {
        secondary = '@Includes';
      }
      const lineWidths = lines.map(line => context.measureText(line).width);
      context.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
      const routeSpacing = node.renderKind === 'route' && !node.labelBelow ? ROUTE_GAP * camera.scale : Infinity;
      const subtitle = routeSpacing >= 40 ? trimLabel(secondary, maxWidth) : '';
      const captionLine = routeSpacing >= (subtitle ? 56 : 40) ? trimLabel(displayText(caption), maxWidth) : '';
      const labelWidth = Math.max(...lineWidths, context.measureText(subtitle).width, context.measureText(captionLine).width);
      const lineCount = lines.length + Number(Boolean(subtitle)) + Number(Boolean(captionLine));
      const x = labelOnLeft ? labelX - labelWidth - 14 : labelX + node.radius + 10;
      const y = labelY + (node.labelBelow ? 12 : -10);
      const rect = { x: x - 5, y: y - 3, width: labelWidth + 10, height: lineCount * 16 + 4 };
      if (rect.x + rect.width < 0 || rect.x > width || rect.y > height - 12) {
        continue;
      }
      if (!selected && !hovered && !node.isGraph && overlaps(rect)) {
        continue;
      }
      reserve(rect);
      labelRects.push(rect);
      context.globalAlpha = node.opacity * labelOpacity * (active ? 0.97 : 0.75);
      context.fillStyle = '#0b1017';
      context.fillRect(rect.x, rect.y, rect.width, rect.height);
      context.globalAlpha = opacity * labelOpacity;
      context.textAlign = 'left';
      context.font = (selected || node.group || node.isGraph ? '600 ' : '400 ') + '12px -apple-system, BlinkMacSystemFont, sans-serif';
      context.fillStyle = selected || hovered || node.group || node.isGraph ? '#e7edf5' : '#b6c3d2';
      lines.forEach((line, index) => context.fillText(line, x, y + 11 + index * 16));
      context.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
      let nextY = y + 11 + lines.length * 16;
      if (subtitle) {
        context.fillStyle = node.isGraph || node.isEntryPoint ? '#71c8a0' : '#8c9cae';
        context.fillText(subtitle, x, nextY);
        nextY += 16;
      }
      if (captionLine) {
        context.fillStyle = '#91a2b5';
        context.fillText(captionLine, x, nextY);
      }
      node.labelBounds = rect;
    }
    context.globalAlpha = 1;
    if (!drawing.nodes.length) {
      context.fillStyle = '#8290a2';
      context.textAlign = 'center';
      context.font = '14px -apple-system, BlinkMacSystemFont, sans-serif';
      context.fillText('No bindings in this view. Choose a binding or adjust the filters.', width / 2, height / 2);
    }
    hasPainted = true;
    if (!moving) {
      staticContext.setTransform(1, 0, 0, 1, 0, 0);
      staticContext.drawImage(canvas, 0, 0);
    }
    if (animate) {
      paintTraffic(timestamp);
    }
    if (animate || moving) {
      frame = requestAnimationFrame(paint);
    }
  }

  function paintTraffic(timestamp) {
    for (const path of trafficPaths) {
      if (path.length < 1) {
        continue;
      }
      const point = pointAlong(path.points, (timestamp * 0.033 + path.index * 57) % path.length);
      if (labelRects.some(rect => point.x >= rect.x - 3 && point.x <= rect.x + rect.width + 3 && point.y >= rect.y - 3 && point.y <= rect.y + rect.height + 3)) {
        continue;
      }
      context.globalAlpha = path.opacity;
      context.fillStyle = path.color;
      context.beginPath();
      context.arc(point.x, point.y, 2, 0, Math.PI * 2);
      context.fill();
    }
    context.globalAlpha = 1;
  }

  function hitRegionName(point, labels = labelRects) {
    for (let index = labels.length - 1; index >= 0; index--) {
      const label = labels[index];
      if (!label.regionId || label.exiting || label.opacity <= 0.15) {
        continue;
      }
      if (point.x >= label.x && point.x <= label.x + label.width && point.y >= label.y && point.y <= label.y + label.height) {
        return label;
      }
    }
    return null;
  }

  function hitTest(point) {
    let nearest = null;
    let nearestDistance = Infinity;
    for (const node of screenNodes) {
      const distance = Math.hypot(node.x - point.x, node.y - point.y);
      const radius = Math.max(10, (node.radius || 5) + 4);
      if (distance <= radius && distance < nearestDistance) {
        nearest = node;
        nearestDistance = distance;
      }
    }
    if (nearest) {
      return nearest;
    }
    for (let index = screenNodes.length - 1; index >= 0; index--) {
      const node = screenNodes[index];
      const bounds = node.labelBounds;
      if (bounds && point.x >= bounds.x && point.x <= bounds.x + bounds.width && point.y >= bounds.y && point.y <= bounds.y + bounds.height) {
        return node;
      }
    }
    return null;
  }

  function canvasPoint(event) {
    const rect = canvas.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  function updateMotion() {
    if (!motionEnabled()) {
      finishTransitions();
    }
    const control = element('pause-motion');
    const label = state.paused ? 'Resume motion' : 'Pause motion';
    control.textContent = state.paused ? '▶' : 'Ⅱ';
    control.title = label;
    control.setAttribute('aria-label', label);
    control.setAttribute('aria-pressed', String(state.paused));
    invalidate();
  }

  function clearSelection() {
    if (!state.selected && !state.chain) {
      return;
    }
    if (state.chain && !state.selected) {
      clearChain();
      return;
    }
    const focusOverview = document.activeElement?.matches('#browse-selection-clear, #clear-map-selection, #selection-clear');
    state.selected = null;
    state.exploration = null;
    state.routeTarget = null;
    state.returnView = null;
    renderBindings();
    switchMode('overview');
    if (focusOverview) {
      document.querySelector('.view-switcher [data-mode="overview"]').focus({ preventScroll: true });
    }
    announce('Selection cleared. Showing Overview.');
  }

  function focusChain() {
    resize();
    if (state.mode === 'route') {
      focusRoute();
      return;
    }
    const start = view.nodes.find(node => node.id === longestPath[longestPath.length - 1]);
    if (start) {
      const scale = 0.85;
      const viewport = mapViewport();
      const top = viewport.top + 46;
      moveCamera({ x: start.x + (width / 2 - viewport.left - 106) / scale, y: start.y + (height / 2 - top) / scale, scale });
    }
  }

  function clearChain() {
    state.chain = false;
    if (state.mode === 'route') {
      state.mode = state.chainLayout;
    }
    refreshView();
  }

  function setExpandedMap(expanded) {
    state.expanded = expanded;
    element('app').classList.toggle('map-only', expanded);
    document.body.classList.toggle('map-expanded', expanded);
    const control = element('expand-map');
    element('expand-map-label').textContent = expanded ? 'Exit expanded map' : 'Expand map';
    control.title = expanded ? 'Exit expanded map (F, or Esc after clearing selection)' : 'Expand map (F)';
    control.setAttribute('aria-label', expanded ? 'Exit expanded map' : 'Expand map');
    control.setAttribute('aria-pressed', String(expanded));
    if (expanded) {
      canvas.focus();
    } else {
      control.focus();
    }
    resize();
    fitView();
    announce(expanded ? 'Map expanded. Press F to return. Escape clears selection first.' : 'Map panels restored.');
  }

  element('graph-name').textContent = shortName(metroData.graphName);
  element('graph-name').title = metroData.graphName;
  initializeBindingGraphFilter();
  element('graph-summary').textContent = model.bindingNodes.length.toLocaleString() + ' bindings · ' + model.packages.size.toLocaleString() + ' packages';
  element('longest-path').disabled = !(metroData.longestPath || []).length;
  element('show-root').disabled = !model.root;
  element('show-root').addEventListener('click', () => showRoot());
  element('show-entry-points').addEventListener('click', () => showRoot(true));
  element('clear-map-selection').addEventListener('click', clearSelection);
  element('browse-selection-clear').addEventListener('click', clearSelection);
  element('back-to-graph').addEventListener('click', returnToGraph);
  element('selected-binding-name').addEventListener('click', () => {
    if (!view.nodes.some(node => node.id === state.selected)) {
      state.mode = 'full';
      refreshView(true);
      renderDetails();
    }
    revealSelection();
  });
  element('direction').value = state.direction;
  element('flow-direction').addEventListener('change', event => {
    state.flowDirection = event.target.value;
    refreshView();
    resize();
    fitView();
    announce('Graph direction: ' + element('flow-direction').selectedOptions[0].textContent);
  });
  element('depth').value = String(state.depth);
  element('show-synthetic').checked = state.synthetic;
  element('show-defaults').checked = state.defaults;
  element('show-extensions').checked = state.extensions;
  element('extensions-control').hidden = extensionRegions.size === 0;
  element('show-extensions').addEventListener('change', event => setExtensions(event.target.checked));
  element('search').addEventListener('input', event => {
    state.query = event.target.value;
    state.listLimit = LIST_PAGE;
    state.listFocus = -1;
    renderBindings();
  });
  element('search-clear').addEventListener('click', () => {
    element('search').value = '';
    state.query = '';
    renderBindings();
    element('search').focus();
  });
  element('sort-mode').addEventListener('change', event => {
    state.sort = event.target.value;
    state.listLimit = LIST_PAGE;
    renderBindings();
  });
  document.querySelectorAll('[data-mode]:not([data-context-view])').forEach(control => control.addEventListener('click', () => switchMode(control.dataset.mode)));
  element('longest-path').addEventListener('click', () => {
    if (state.chain) {
      clearChain();
      return;
    }
    if (chainNodes.some(id => !inGraphScope(model.byId.get(id)))) {
      enableExtensions();
    }
    if (!['full', 'radial', 'circular'].includes(state.mode)) {
      state.mode = 'full';
    }
    state.chainLayout = state.mode;
    state.selected = null;
    state.chain = true;
    renderDetails();
    renderBindings();
    refreshView();
    focusChain();
  });
  element('focus-chain').addEventListener('click', focusChain);
  element('clear-chain').addEventListener('click', clearChain);
  element('isolate-chain').addEventListener('click', () => {
    state.mode = state.mode === 'route' ? state.chainLayout : 'route';
    refreshView();
    focusChain();
  });
  for (const [id, key] of [['direction', 'direction'], ['depth', 'depth']]) {
    element(id).addEventListener('change', event => {
      state[key] = key === 'depth' ? Number(event.target.value) : event.target.value;
      state.limit = INITIAL_LIMIT;
      refreshView();
    });
  }
  for (const [id, key] of [['show-synthetic', 'synthetic'], ['show-defaults', 'defaults']]) {
    element(id).addEventListener('change', event => {
      state[key] = event.target.checked;
      refreshView();
    });
  }
  if (element('edge-types')) {
    element('edge-types').addEventListener('change', event => {
      state.edgeType = event.target.value;
      refreshView();
    });
  }
  element('load-more').addEventListener('click', () => {
    state.limit += INITIAL_LIMIT;
    refreshView();
  });
  element('selection-clear').addEventListener('click', clearSelection);
  element('zoom-in').addEventListener('click', () => zoom(1.3));
  element('zoom-out').addEventListener('click', () => zoom(1 / 1.3));
  element('fit-view').addEventListener('click', () => {
    resize();
    fitView();
  });
  element('expand-map').addEventListener('click', () => setExpandedMap(!state.expanded));
  element('pause-motion').addEventListener('click', () => {
    state.paused = !state.paused;
    updateMotion();
  });
  motionPreference.addEventListener('change', event => {
    state.paused = event.matches;
    updateMotion();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      finishTransitions();
    }
    invalidate();
  });
  document.addEventListener('click', event => {
    if ((!state.selected && !state.chain) || event.target === canvas) {
      return;
    }
    const control = event.target.closest('button, a, input, select, textarea, summary, label, [role="button"], [role="checkbox"], #selection-toolbar, .inspector-panel, .help');
    if (!control && window.getSelection()?.isCollapsed !== false) {
      clearSelection();
    }
  });
  document.addEventListener('keydown', event => {
    const editing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName || '');
    const plainKey = !event.metaKey && !event.ctrlKey && !event.altKey;
    if (event.key.toLowerCase() === 'f' && plainKey && !editing && !event.repeat) {
      event.preventDefault();
      setExpandedMap(!state.expanded);
      return;
    }
    if (event.key === '/' && !editing) {
      event.preventDefault();
      if (state.expanded) {
        setExpandedMap(false);
      }
      element('search').focus();
      element('search').select();
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      if (editing) {
        document.activeElement.blur();
      } else if (state.selected || state.chain) {
        clearSelection();
      } else if (state.expanded) {
        setExpandedMap(false);
      }
      return;
    }
    if (document.activeElement === element('search') && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault();
      const rows = element('binding-list').querySelectorAll('.binding-item');
      if (rows.length) {
        state.listFocus = event.key === 'ArrowDown' ? 0 : rows.length - 1;
        rows[state.listFocus].focus();
      }
    }
    if (document.activeElement === element('search') && event.key === 'Enter' && listNodes.length) {
      event.preventDefault();
      selectNode(listNodes[0].id, true);
    }
  });
  element('binding-list').addEventListener('keydown', event => {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      return;
    }
    const rows = Array.from(element('binding-list').querySelectorAll('.binding-item'));
    const index = rows.indexOf(document.activeElement);
    if (index < 0) {
      return;
    }
    event.preventDefault();
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1 : Math.max(0, Math.min(rows.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)));
    rows[next].focus();
  });
  let drag = null;
  canvas.addEventListener('pointerdown', event => {
    if (event.button !== 0) {
      return;
    }
    dismissStartHint();
    stopCamera();
    const point = canvasPoint(event);
    drag = { ...point, initialX: point.x, initialY: point.y, moved: false };
    state.hoveredRegion = null;
    invalidate();
    canvas.setPointerCapture(event.pointerId);
    canvas.style.cursor = 'grabbing';
  });
  canvas.addEventListener('pointermove', event => {
    pointerPosition = { clientX: event.clientX, clientY: event.clientY };
    const point = canvasPoint(event);
    if (drag) {
      if (Math.hypot(point.x - drag.initialX, point.y - drag.initialY) > 4) {
        drag.moved = true;
      }
      if (drag.moved) {
        camera.x -= (point.x - drag.x) / camera.scale;
        camera.y -= (point.y - drag.y) / camera.scale;
        drag.x = point.x;
        drag.y = point.y;
        element('canvas-tooltip').hidden = true;
        invalidate();
      }
      return;
    }
    const region = hitRegionName(point);
    const hit = region ? null : hitTest(point);
    const previous = state.hovered;
    const previousRegion = state.hoveredRegion;
    state.hovered = hit?.id || null;
    state.hoveredRegion = region?.regionId || null;
    canvas.style.cursor = region ? 'default' : hit ? 'pointer' : 'grab';
    const tooltip = element('canvas-tooltip');
    tooltip.hidden = !hit;
    if (hit) {
      const expandHint = state.mode === 'explore' && !hit.group && !hit.isGraph ? (state.exploration?.expanded.has(hit.id) ? ' · Click to collapse connections' : ' · Click to expand connections') : '';
      tooltip.textContent = hit.group ? (packageName(hit.pkg)) + ' · ' + hit.packages.length + ' packages · ' + hit.count + ' bindings' : displayText(hit.fullKey) + (hit.isGraphInput ? ' · supplied graph input' : '') + expandHint;
      tooltip.style.left = Math.max(8, Math.min(width - 290, point.x + 16)) + 'px';
      tooltip.style.top = Math.max(8, Math.min(height - 60, point.y + 20)) + 'px';
    }
    if (previous !== state.hovered || previousRegion !== state.hoveredRegion) {
      invalidate();
    }
  });
  canvas.addEventListener('pointerup', event => {
    if (!drag || event.button !== 0) {
      return;
    }
    const wasDragging = drag?.moved;
    drag = null;
    canvas.style.cursor = 'grab';
    if (!wasDragging) {
      const point = canvasPoint(event);
      const hit = hitRegionName(point) ? null : hitTest(point);
      if (hit?.group) {
        selectPackage(hit.pkg, hit.packages);
      } else if (hit) {
        toggleStation(hit.id);
      } else {
        clearSelection();
      }
    }
  });
  canvas.addEventListener('pointercancel', () => {
    drag = null;
    pointerPosition = null;
    state.hoveredRegion = null;
    invalidate();
  });
  canvas.addEventListener('pointerleave', () => {
    pointerPosition = null;
    state.hoveredRegion = null;
    if (!drag) {
      state.hovered = null;
      element('canvas-tooltip').hidden = true;
      invalidate();
    }
  });
  canvas.addEventListener('wheel', event => {
    event.preventDefault();
    zoom(Math.exp(-event.deltaY * 0.0015), canvasPoint(event));
  }, { passive: false });
  canvas.addEventListener('keydown', event => {
    if (event.metaKey || event.ctrlKey || event.altKey) {
      return;
    }
    const movement = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[event.key];
    if (movement) {
      event.preventDefault();
      dismissStartHint();
      stopCamera();
      camera.x += movement[0] * 80 / camera.scale;
      camera.y += movement[1] * 80 / camera.scale;
      invalidate();
    } else if (event.key === '+' || event.key === '=') {
      event.preventDefault();
      zoom(1.3);
    } else if (event.key === '-') {
      event.preventDefault();
      zoom(1 / 1.3);
    } else if (event.key === 'Home') {
      event.preventDefault();
      showRoot();
    }
  });
  canvas.addEventListener('dblclick', () => {
    resize();
    fitView();
  });
  const resize = () => {
    const bounds = canvas.getBoundingClientRect();
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    const sameSize = bounds.width === width && bounds.height === height;
    const samePosition = bounds.left === canvasBounds?.left && bounds.top === canvasBounds?.top;
    if (sameSize && samePosition && ratio === pixelRatio) {
      return;
    }
    const first = width === 1;
    const previousWidth = width;
    const previousHeight = height;
    const previousBounds = canvasBounds;
    canvasBounds = bounds;
    width = Math.max(1, bounds.width);
    height = Math.max(1, bounds.height);
    pixelRatio = ratio;
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    staticCanvas.width = canvas.width;
    staticCanvas.height = canvas.height;
    if (first) {
      openView();
    } else {
      if (hasPainted && motionEnabled() && previousBounds) {
        sampleCamera(performance.now());
        const target = cameraTransition?.to || { ...camera };
        cameraTransition = null;
        camera.x += ((width - previousWidth) / 2 + bounds.left - previousBounds.left) / camera.scale;
        camera.y += ((height - previousHeight) / 2 + bounds.top - previousBounds.top) / camera.scale;
        moveCamera(target);
      }
      invalidate();
    }
  };
  renderPackages();
  renderBindings();
  renderDetails();
  refreshView();
  updateMotion();
  new ResizeObserver(resize).observe(canvas);
  resize();
  if (metroData.initialRegionId) {
    showRegion(metroData.initialRegionId);
  }
})();
