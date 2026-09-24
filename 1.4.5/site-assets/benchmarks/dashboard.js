'use strict';

/** The published before/after schema has no run ID or recorded runner hardware. */
const BenchmarkHistory = (() => {
  // These rounded bands use the spread of 423 pairs per metric through September 8, 2026.
  const variationBands = { 'Build Time': 7, Startup: 5 };

  /** The estimated band applies to each metric without rounding the measured delta first. */
  function changeStatus(before, after, variationPercent) {
    const withinNoise = after >= before * (1 - variationPercent / 100) && after <= before * (1 + variationPercent / 100);
    if (withinNoise) {
      return 'noise';
    }
    return after < before ? 'faster' : 'slower';
  }

  /** A median gives shared timing spikes some context without smoothing the measurements. */
  function median(values) {
    const sorted = [...values].sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);
    if (sorted.length % 2 === 0) {
      return (sorted[middle - 1] + sorted[middle]) / 2;
    }
    return sorted[middle];
  }

  /** Match consecutive measurements by role and identity while preserving repeated runs. */
  function collectRuns(entries, benchmarkName) {
    const variationPercent = variationBands[benchmarkName];
    const runs = [];
    let pending = null;
    let measurements = 0;
    for (const entry of entries) {
      for (const bench of entry.benches || []) {
        if (bench.name !== benchmarkName) {
          continue;
        }
        measurements++;
        const id = entry.commit?.id || '';
        const isBefore = id.endsWith('~1') && /^before\b/.test(bench.extra || '');
        if (isBefore) {
          pending = { entry, bench };
          continue;
        }
        const isAfter = /^after\b/.test(bench.extra || '');
        if (!pending || !isAfter) {
          pending = null;
          continue;
        }
        const before = pending;
        pending = null;
        const sameCommit = id.startsWith(before.entry.commit.id.slice(0, -2));
        const sameMetric = bench.unit === before.bench.unit && entry.tool === before.entry.tool;
        const validValues = [before.bench.value, bench.value].every(value => Number.isFinite(value) && value > 0);
        const validDates = Number.isFinite(entry.date) && Number.isFinite(before.entry.date) && entry.date >= before.entry.date;
        if (!sameCommit || !sameMetric || !validValues || !validDates) {
          continue;
        }
        runs.push({
          commit: entry.commit,
          date: entry.date,
          before: before.bench.value,
          after: bench.value,
          unit: bench.unit,
          delta: ((bench.value - before.bench.value) / before.bench.value) * 100,
          status: changeStatus(before.bench.value, bench.value, variationPercent),
        });
      }
    }
    runs.sort((a, b) => a.date - b.date);
    for (let index = 0; index < runs.length; index++) {
      const run = runs[index];
      // A complete trailing window keeps the trend comparable across history filters.
      const trendWindow = runs.slice(Math.max(0, index - 9), index + 1);
      run.trend = trendWindow.length === 10 ? median(trendWindow.map(item => item.after)) : null;
      const history = runs.slice(Math.max(0, index - 10), index);
      run.variation = null;
      if (history.length < 5) {
        continue;
      }
      run.contextPairs = history.length;
      const beforeMedian = median(history.map(item => item.before));
      const afterMedian = median(history.map(item => item.after));
      run.beforeShift = ((run.before - beforeMedian) / beforeMedian) * 100;
      run.afterShift = ((run.after - afterMedian) / afterMedian) * 100;
      // Comparing timings directly keeps inclusive thresholds stable at their boundaries.
      const sharedSlowdown = run.before >= beforeMedian * 1.2 && run.after >= afterMedian * 1.2;
      const sharedSpeedup = run.before <= beforeMedian * 0.8 && run.after <= afterMedian * 0.8;
      if (run.status === 'noise') {
        if (sharedSlowdown) {
          run.variation = 'slower';
        } else if (sharedSpeedup) {
          run.variation = 'faster';
        }
      }
    }
    return { runs, omitted: measurements - runs.length * 2, variationPercent };
  }

  /** Keep the stored data intact and calculate runner context before narrowing the view. */
  function visibleRuns(runs, range) {
    if (range === 'all') {
      return runs;
    }
    return runs.slice(-Number(range));
  }

  /** Summarize the selected range with disjoint endpoint windows and separate paired deltas. */
  function summarizeRuns(runs) {
    if (!runs.length) {
      return null;
    }
    // Endpoint comparisons shrink both windows to keep their measurements separate.
    const windowSize = Math.min(10, Math.max(1, Math.floor(runs.length / 2)));
    const startMedian = median(runs.slice(0, windowSize).map(run => run.after));
    const endMedian = median(runs.slice(-windowSize).map(run => run.after));
    const periodChange = runs.length > 1 ? ((endMedian - startMedian) / startMedian) * 100 : null;
    return {
      windowSize, startMedian, endMedian, periodChange,
      medianDelta: median(runs.map(run => run.delta)),
      withinVariation: runs.filter(run => run.status === 'noise').length,
    };
  }

  /** Build time leads at both existing URLs; the original data files keep their paths. */
  function pageConfig(href) {
    const page = new URL(href);
    const directory = new URL('.', page);
    const root = directory.pathname.endsWith('/build/') ? new URL('../', directory) : directory;
    const isBuild = page.searchParams.get('benchmark') !== 'startup';
    return { root, isBuild, dataUrl: new URL(isBuild ? 'build/data.js' : 'data.js', root) };
  }
  return { collectRuns, visibleRuns, summarizeRuns, pageConfig, changeStatus, variationBands };
})();

// Node can validate the data rules without loading a browser or Chart.js.
if (typeof module !== 'undefined' && module.exports) {
  module.exports = BenchmarkHistory;
}

if (typeof document !== 'undefined') {
  loadDashboard();
}

/** Load the chosen existing history before rendering its paired measurements. */
function loadDashboard() {
  const config = BenchmarkHistory.pageConfig(window.location.href);
  const script = document.createElement('script');
  script.src = config.dataUrl.href;
  script.onload = () => initializeDashboard(config);
  script.onerror = () => {
    const error = document.getElementById('error');
    error.hidden = false;
    error.textContent = 'Benchmark data could not be loaded. Reload this page to try again.';
  };
  document.head.append(script);
}

/** Both charts expose paired measurements in tooltips for the selected history range. */
function initializeDashboard({ root, isBuild }) {
  const element = id => document.getElementById(id);
  const data = window.BENCHMARK_DATA;
  const error = element('error');
  if (!data || !data.entries) {
    error.hidden = false;
    error.textContent = 'Benchmark data could not be loaded. Reload this page to try again.';
    return;
  }
  const benchmarkName = isBuild ? 'Build Time' : 'Startup';
  const entries = data.entries[isBuild ? 'Build Time Benchmark' : 'Startup Benchmark'] || [];
  const { runs, omitted, variationPercent } = BenchmarkHistory.collectRuns(entries, benchmarkName);
  const dateFormat = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric', year: 'numeric' });
  const shortDate = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric' });
  const title = `${isBuild ? 'Build' : 'Startup'} performance`;
  document.title = `${title} · Metro benchmarks`;
  element('page-title').textContent = title;
  element('trend-title').textContent = `${isBuild ? 'Build time' : 'Startup time'} history`;
  element('recent-time-label').textContent = isBuild ? 'Recent build time' : 'Recent startup time';
  element('variation-label').textContent = `Estimated typical variation: ±${variationPercent}%`;
  element('faster-label').textContent = `Below −${variationPercent}%: measured faster`;
  element('slower-label').textContent = `Above +${variationPercent}%: measured slower`;
  element('change-chart').setAttribute('aria-label', `Connected history of same-run percentage changes. The shaded range from minus ${variationPercent} to plus ${variationPercent} percent shows estimated typical variation.`);
  element(isBuild ? 'build-link' : 'startup-link').setAttribute('aria-current', 'page');

  // Navigation uses the directory layout independently of the selected benchmark.
  element('startup-link').href = new URL('?benchmark=startup', root).href;
  element('build-link').href = new URL('build/', root).href;
  if (Number.isFinite(data.lastUpdate)) {
    element('last-update').textContent = dateFormat.format(data.lastUpdate);
    element('last-update').dateTime = new Date(data.lastUpdate).toISOString();
  }
  element('download').disabled = false;
  element('download').onclick = () => {
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${isBuild ? 'build' : 'startup'}-benchmarks.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  if (!runs.length) {
    error.hidden = false;
    error.textContent = 'No complete before/after pairs are available yet. The JSON download contains all recorded measurements.';
    return;
  }

  /** Display build milliseconds as seconds while preserving the original values in JSON. */
  function time(value, digits = isBuild ? 2 : 3) {
    if (isBuild) {
      return `${(value / 1000).toFixed(digits)} s`;
    }
    return `${value.toFixed(digits)} ms/op`;
  }
  function percentage(value) {
    const rounded = Number(value.toFixed(2));
    return `${rounded > 0 ? '+' : ''}${rounded.toFixed(2)}%`;
  }
  const latest = runs[runs.length - 1];
  element('dashboard').hidden = false;
  element('run-count').textContent = runs.length.toLocaleString('en');
  element('history-span').textContent = `${dateFormat.format(runs[0].date)} – ${dateFormat.format(latest.date)}`;
  if (omitted) {
    element('omitted-note').hidden = false;
    element('omitted-note').textContent = `${omitted} incomplete or invalid measurements are omitted from the charts. They remain available in the JSON download.`;
  }

  let charts = [];

  /** Rebuild charts from the chosen slice; historical context stays attached to each run. */
  function render() {
    const visible = BenchmarkHistory.visibleRuns(runs, element('range').value);
    const summary = BenchmarkHistory.summarizeRuns(visible);
    const summaryPrecision = isBuild ? 2 : 4;
    element('summary-period').textContent = `${dateFormat.format(visible[0].date)} – ${dateFormat.format(visible[visible.length - 1].date)}`;
    element('recent-time').textContent = time(summary.endMedian, summaryPrecision);
    element('recent-time-detail').textContent = `Median of last ${summary.windowSize} ${summary.windowSize === 1 ? 'pair' : 'pairs'}`;
    element('period-change').textContent = summary.periodChange === null ? '—' : percentage(summary.periodChange);
    element('period-change-detail').textContent = summary.periodChange === null
      ? 'Needs at least two pairs'
      : `${time(summary.startMedian, summaryPrecision)} → ${time(summary.endMedian, summaryPrecision)} · first / last ${summary.windowSize} medians`;
    element('median-change').textContent = percentage(summary.medianDelta);
    element('median-change-detail').textContent = `${summary.withinVariation} of ${visible.length} pairs within ±${variationPercent}%`;
    element('visible-count').textContent = `${visible.length} matched pairs`;
    charts.forEach(chart => chart.destroy());
    charts = [];
    if (typeof Chart === 'undefined') {
      error.hidden = false;
      error.textContent = 'Charts could not be loaded. You can still download the complete data as JSON.';
      document.querySelectorAll('.chart-container').forEach(container => { container.hidden = true; });
      return;
    }

    const labels = visible.map(run => shortDate.format(run.date));
    // Canvas colors share the CSS palette used by the surrounding legends and controls.
    const theme = getComputedStyle(document.documentElement);
    const color = name => theme.getPropertyValue(name).trim();
    const blue = color('--subway-blue');
    const red = color('--subway-red');
    const yellow = color('--subway-yellow');
    const surface = color('--surface');
    const neutral = color('--subway-gray');
    const pointColor = run => ({ noise: neutral, faster: blue, slower: red })[run.status];
    Chart.defaults.global.defaultFontFamily = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif';
    Chart.defaults.global.defaultFontColor = color('--muted');

    /** Both plots use the same run order and expose exact paired values in their tooltips. */
    function options(change) {
      return {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 0 },
        hover: { animationDuration: 0, mode: 'index', intersect: false },
        legend: { display: false },
        layout: { padding: { top: 8 } },
        scales: {
          xAxes: [{ offset: true, gridLines: { display: false }, ticks: { maxTicksLimit: 7, maxRotation: 0 } }],
          yAxes: [{
            afterFit: scale => { scale.width = 70; },
            gridLines: { color: color('--chart-grid'), zeroLineColor: color('--chart-zero'), drawBorder: false },
            scaleLabel: { display: true, labelString: change ? 'Change (%)' : isBuild ? 'Seconds' : 'ms / operation' },
            ticks: {
              beginAtZero: true,
              suggestedMin: change ? -10 : 0,
              suggestedMax: change ? 10 : undefined,
              maxTicksLimit: 6,
              callback: value => change ? `${value > 0 ? '+' : ''}${value}%` : value,
            },
          }],
        },
        tooltips: {
          mode: 'index', intersect: false, displayColors: false,
          backgroundColor: color('--page'), titleFontColor: color('--ink'), bodyFontColor: color('--ink'), titleFontSize: 13, bodyFontSize: 12,
          borderColor: color('--chart-zero'), borderWidth: 1,
          xPadding: 14, yPadding: 12, cornerRadius: 7,
          filter: item => item.datasetIndex === 0,
          callbacks: {
            title: items => {
              const run = visible[items[0].index];
              return `${dateFormat.format(run.date)} · ${run.commit.id.slice(0, 8)}`;
            },
            label: item => {
              const run = visible[item.index];
              return ['Same GitHub Actions runner', `Parent commit  ${time(run.before)}`, `This commit       ${time(run.after)}`, `Paired change   ${percentage(run.delta)}`];
            },
            afterBody: items => {
              const run = visible[items[0].index];
              const notes = [];
              if (run.status === 'noise') {
                notes.push(`Within estimated variation (±${variationPercent}%)`);
              }
              if (!change && run.trend !== null) {
                notes.push(`10-pair median  ${time(run.trend)}`);
              }
              if (!change && run.variation) {
                notes.push(`◇ Possibly a ${run.variation} CI runner`);
                notes.push(`Both times versus previous ${run.contextPairs} pairs:`);
                notes.push(`Parent ${percentage(run.beforeShift)} · current ${percentage(run.afterShift)}`);
                notes.push('Workload or tool changes can also cause this.');
              }
              return notes;
            },
          },
        },
      };
    }

    // The fixed band keeps the variation estimate consistent across history filters.
    const referenceBand = {
      beforeDraw(chart) {
        const scale = chart.scales['y-axis-0'];
        const area = chart.chartArea;
        const top = scale.getPixelForValue(variationPercent);
        const bottom = scale.getPixelForValue(-variationPercent);
        chart.ctx.save();
        chart.ctx.fillStyle = color('--chart-band');
        chart.ctx.fillRect(area.left, top, area.right - area.left, bottom - top);
        chart.ctx.strokeStyle = color('--chart-zero');
        chart.ctx.setLineDash([4, 4]);
        for (const y of [top, bottom]) {
          chart.ctx.beginPath();
          chart.ctx.moveTo(area.left, y);
          chart.ctx.lineTo(area.right, y);
          chart.ctx.stroke();
        }
        chart.ctx.restore();
      },
    };
    charts.push(new Chart(element('change-chart'), {
      type: 'line',
      data: { labels, datasets: [
        { data: visible.map(run => run.delta), borderColor: neutral, borderWidth: 1.5, fill: false, lineTension: 0, pointRadius: visible.map(run => run.status === 'noise' ? 1 : 4), pointBackgroundColor: visible.map(pointColor), pointBorderColor: visible.map(pointColor), pointBorderWidth: 1, pointHitRadius: 10 },
      ] },
      options: options(true),
      plugins: [referenceBand],
    }));
    const plotValue = value => isBuild ? value / 1000 : value;
    // Connected measurements show timing history; the median emphasizes sustained movement.
    const timingChart = new Chart(element('timing-chart'), {
      type: 'line',
      data: { labels, datasets: [
        { label: 'Parent commit', data: visible.map(run => plotValue(run.before)), borderColor: neutral, borderWidth: 1, borderDash: [4, 4], fill: false, lineTension: 0, pointRadius: 0, pointHitRadius: 10 },
        { label: 'This commit', data: visible.map(run => plotValue(run.after)), borderColor: blue, borderWidth: 1.5, fill: false, lineTension: 0, pointRadius: visible.map(run => run.variation ? 5 : 0), pointStyle: visible.map(run => run.variation ? 'rectRot' : 'circle'), pointBackgroundColor: surface, pointBorderColor: yellow, pointBorderWidth: 2, pointHitRadius: 10 },
        { label: '10-pair median', data: visible.map(run => run.trend === null ? null : plotValue(run.trend)), borderColor: color('--trend'), borderWidth: 3, fill: false, lineTension: 0, pointRadius: 0, pointHitRadius: 0 },
      ] },
      options: options(false),
    });
    charts.push(timingChart);
  }
  element('range').onchange = render;
  render();
}
