/* Copyright (C) 2026 Zac Sweers */
/* SPDX-License-Identifier: Apache-2.0 */

const element = id => document.getElementById(id);
let assetsPromise;
let currentWorker = null;
let requestId = 0;
let currentFiles = [];
let currentSummary = null;
let currentGraph = null;
let currentData = null;
let activeFrame = null;
let busy = false;
let pickerMode = 'replace';
let dragDepth = 0;

async function readAsset(name) {
  const response = await fetch(name);
  if (!response.ok) {
    throw new Error('Could not load ' + name + ' (' + response.status + '). Reload the page and try again.');
  }
  return response.text();
}

function loadAssets() {
  if (!assetsPromise) {
    assetsPromise = Promise.all([
      readAsset('graph-viewer.html'),
      readAsset('graph-viewer.css'),
      readAsset('graph-viewer.js'),
      readAsset('pluginIcon_dark.svg'),
    ]).then(([template, style, script, icon]) => ({ template, style, script, icon }));
    assetsPromise.catch(() => { assetsPromise = null; });
  }
  return assetsPromise;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}

function embeddedJson(value) {
  return JSON.stringify(value).replace(/[<>&\u2028\u2029]/g, character => '\\u' + character.charCodeAt(0).toString(16).padStart(4, '0'));
}

function frameDocument(assets, data) {
  const replacements = {
    __METRO_TITLE__: escapeHtml(data.graphName),
    __METRO_INDEX_URL__: '#',
    __METRO_ICON_URL__: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(assets.icon),
    __METRO_STYLE__: assets.style,
    __METRO_SCRIPT__: assets.script,
    __METRO_DATA__: embeddedJson({ ...data, hosted: true }),
  };
  const html = assets.template.replace(/__METRO_[A-Z_]+__/g, token => {
    if (!Object.hasOwn(replacements, token)) {
      throw new Error('The viewer template contains an unsupported placeholder: ' + token);
    }
    return replacements[token];
  });
  const policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'";
  return html.replace('<head>', '<head><meta http-equiv="Content-Security-Policy" content="' + policy + '">');
}

function setBusy(value, message = 'Reading reports…') {
  busy = value;
  element('loading').hidden = !value;
  element('loading-text').textContent = message;
  element('workspace').setAttribute('aria-busy', String(value));
  for (const id of ['choose-reports', 'load-sample', 'add-reports', 'replace-reports', 'graph-select']) {
    element(id).disabled = value;
  }
}

function showError(error) {
  const message = error?.message || String(error);
  element('import-error').textContent = message;
  element('import-error').hidden = false;
  element('status').textContent = 'Import failed. ' + message;
}

function paintBeforeWork() {
  return new Promise(resolve => requestAnimationFrame(() => setTimeout(resolve, 0)));
}

function workerRequest(worker, request) {
  const id = ++requestId;
  return new Promise((resolve, reject) => {
    const onMessage = event => {
      if (event.data.id !== id) {
        return;
      }
      cleanup();
      if (event.data.error) {
        reject(new Error(event.data.error));
      } else {
        resolve(event.data);
      }
    };
    const onError = event => {
      cleanup();
      reject(new Error(event.message || 'The report importer could not start. Reload the page and try again.'));
    };
    function cleanup() {
      worker.removeEventListener('message', onMessage);
      worker.removeEventListener('error', onError);
    }
    worker.addEventListener('message', onMessage);
    worker.addEventListener('error', onError);
    worker.postMessage({ ...request, id });
  });
}

function updateReports(summary, graphName, data) {
  const select = element('graph-select');
  select.replaceChildren();
  for (const graph of summary.graphs) {
    const option = document.createElement('option');
    option.value = graph.name;
    option.textContent = graph.label || graph.name;
    option.title = graph.name;
    select.append(option);
  }
  select.value = graphName;
  select.title = graphName;
  element('report-summary').textContent = summary.graphs.length + (summary.graphs.length === 1 ? ' graph loaded' : ' graphs loaded');
  const warnings = summary.warnings || [];
  element('import-warnings').hidden = !warnings.length;
  element('warning-summary').textContent = warnings.length + (warnings.length === 1 ? ' report notice' : ' report notices');
  element('warning-list').replaceChildren();
  for (const warning of warnings) {
    const item = document.createElement('li');
    item.textContent = warning;
    element('warning-list').append(item);
  }
  element('analysis-note').hidden = data.hasAnalysis !== false;
  element('import-error').hidden = true;
  element('welcome').hidden = true;
  element('report-toolbar').hidden = false;
  element('viewer-container').hidden = false;
  document.body.classList.add('has-report');
}

function restoreReports() {
  if (currentData) {
    updateReports(currentSummary, currentGraph, currentData);
  } else {
    element('welcome').hidden = false;
    element('report-toolbar').hidden = true;
    element('viewer-container').hidden = true;
    element('import-warnings').hidden = true;
    element('analysis-note').hidden = true;
    document.body.classList.remove('has-report');
  }
}

function replaceFrame(html, graphName) {
  const frame = document.createElement('iframe');
  frame.title = 'Metro graph: ' + graphName;
  frame.setAttribute('sandbox', 'allow-scripts');
  frame.srcdoc = html;
  frame.className = 'pending-frame';
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      frame.remove();
      reject(new Error('The graph viewer did not finish opening. Try a smaller report or reload the page.'));
    }, 30000);
    const onMessage = event => {
      if (event.source !== frame.contentWindow) {
        return;
      }
      if (event.data?.type === 'metro-viewer-ready') {
        cleanup();
        activeFrame = frame;
        frame.classList.remove('pending-frame');
        document.body.classList.remove('viewer-expanded');
        element('viewer-container').replaceChildren(frame);
        resolve();
      } else if (event.data?.type === 'metro-viewer-error') {
        cleanup();
        frame.remove();
        reject(new Error('The graph viewer could not open this report: ' + event.data.message));
      }
    };
    function cleanup() {
      clearTimeout(timeout);
      window.removeEventListener('message', onMessage);
    }
    window.addEventListener('message', onMessage);
    element('viewer-container').append(frame);
  });
}

async function importReports(files, mode) {
  if (busy || !files.length) {
    return;
  }
  setBusy(true);
  let candidateWorker;
  try {
    await paintBeforeWork();
    const assets = await loadAssets();
    const incoming = await Promise.all(files.map(async file => ({ name: file.name, content: typeof file.content === 'string' ? file.content : await file.text() })));
    const records = mode === 'add' ? currentFiles.slice() : [];
    for (const file of incoming) {
      if (!records.some(existing => existing.name === file.name && existing.content === file.content)) {
        records.push(file);
      }
    }
    candidateWorker = new Worker(new URL('./report-worker.js', import.meta.url), { type: 'module' });
    const { summary, graphName, dataJson } = await workerRequest(candidateWorker, { action: 'import', files: records, graphName: currentGraph });
    setBusy(true, 'Opening graph…');
    await paintBeforeWork();
    const data = JSON.parse(dataJson);
    const html = frameDocument(assets, data);
    updateReports(summary, graphName, data);
    await replaceFrame(html, graphName);
    currentWorker?.terminate();
    currentWorker = candidateWorker;
    candidateWorker = null;
    currentFiles = records;
    currentSummary = summary;
    currentGraph = graphName;
    currentData = data;
    element('status').textContent = 'Opened ' + graphName + '. ' + summary.graphs.length + ' graphs available.';
  } catch (error) {
    candidateWorker?.terminate();
    restoreReports();
    showError(error);
  } finally {
    setBusy(false);
  }
}

async function selectGraph(graphName) {
  if (busy || !currentWorker) {
    return;
  }
  setBusy(true, 'Opening graph…');
  try {
    await paintBeforeWork();
    const assets = await loadAssets();
    const result = await workerRequest(currentWorker, { action: 'render', graphName });
    const data = JSON.parse(result.dataJson);
    const html = frameDocument(assets, data);
    updateReports(currentSummary, graphName, data);
    await replaceFrame(html, graphName);
    currentGraph = graphName;
    currentData = data;
    element('status').textContent = 'Opened ' + graphName + '.';
  } catch (error) {
    restoreReports();
    showError(error);
  } finally {
    setBusy(false);
  }
}

function chooseFiles(mode) {
  pickerMode = mode;
  element('file-picker').click();
}

element('choose-reports').addEventListener('click', () => chooseFiles('replace'));
element('add-reports').addEventListener('click', () => chooseFiles('add'));
element('replace-reports').addEventListener('click', () => chooseFiles('replace'));
element('file-picker').addEventListener('change', event => {
  const files = Array.from(event.target.files);
  event.target.value = '';
  importReports(files, pickerMode);
});
element('graph-select').addEventListener('change', event => selectGraph(event.target.value));
element('load-sample').addEventListener('click', async () => {
  if (busy) {
    return;
  }
  setBusy(true, 'Loading sample…');
  try {
    const content = await readAsset('sample.json');
    setBusy(false);
    await importReports([{ name: 'sample.json', content }], 'replace');
  } catch (error) {
    showError(error);
    setBusy(false);
  }
});

function showDrop(show) {
  element('drop-action').textContent = currentWorker ? 'add them' : 'open them';
  element('drop-overlay').hidden = !show;
}

window.addEventListener('dragenter', event => {
  if (Array.from(event.dataTransfer?.types || []).includes('Files')) {
    event.preventDefault();
    dragDepth++;
    showDrop(!busy);
  }
});
window.addEventListener('dragover', event => {
  if (Array.from(event.dataTransfer?.types || []).includes('Files')) {
    event.preventDefault();
    event.dataTransfer.dropEffect = busy ? 'none' : 'copy';
  }
});
window.addEventListener('dragleave', () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (!dragDepth) {
    showDrop(false);
  }
});
window.addEventListener('drop', event => {
  if (event.dataTransfer?.files.length) {
    event.preventDefault();
    dragDepth = 0;
    showDrop(false);
    importReports(Array.from(event.dataTransfer.files), currentWorker ? 'add' : 'replace');
  }
});
window.addEventListener('message', event => {
  if (!activeFrame || event.source !== activeFrame.contentWindow) {
    return;
  }
  if (event.data?.type === 'metro-viewer-expanded') {
    document.body.classList.toggle('viewer-expanded', event.data.expanded === true);
    const frame = activeFrame;
    const requestId = event.data.requestId;
    requestAnimationFrame(() => {
      if (frame !== activeFrame) {
        return;
      }
      // Apply the frame dimensions before the viewer fits its map.
      frame.getBoundingClientRect();
      frame.contentWindow.postMessage({ type: 'metro-viewer-resized', requestId }, '*');
    });
  } else if (event.data?.type === 'metro-viewer-drop') {
    showDrop(false);
    dragDepth = 0;
    const files = event.data.files;
    if (Array.isArray(files) && files.every(file => file instanceof File)) {
      importReports(files, 'add');
    }
  }
});
