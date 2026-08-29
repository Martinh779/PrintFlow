const API = {
  health: '/api/admin/health',
  nfaStats: '/api/admin/nfa-stats'
};

async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return res.json();
}

function el(tag, cls) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  return node;
}

function showNotice(message, isError = false) {
  const notice = document.getElementById('notice');
  if (!notice) return;
  notice.classList.remove('hidden');
  notice.style.borderColor = isError ? '#d26a6a' : '#c8d4ea';
  notice.style.background = isError ? '#fff1f1' : '#f2f6ff';
  notice.textContent = message;
}

const NFA_DOCS = {
  'NFA-01': {
    verification: 'Serverseitiges Logging unterscheidet 4xx- von 5xx-Antworten. Performance-Client sendet gültige Anfragen; Server loggt Gesamt- und Fehleranzahl. Verhältnis wird berechnet.'
  },
  'NFA-02': {
    verification: 'Performance-Client sendet Anfragen mit konfigurierter Rate, misst per Zeitstempel die Latenz jeder Anfrage und zählt Anfragen mit überschrittener Schwelle.'
  },
  'NFA-03': {
    verification: 'p95-Antwortzeit der REST-Anfragen bei 40 req/s und ≥4 Druckerprozessen darf 500 ms nicht überschreiten.'
  },
  'NFA-04': {
    verification: 'Performance-Client führt 100 gleichzeitige gemischte Operationen durch. Anschließend wird geprüft, dass jeder Auftrag ≤1 Drucker zugewiesen, gestartete nicht storniert, keine Statusänderung verloren.'
  },
  'NFA-05': {
    verification: 'Testauftrag überwachen. Druckerprozess protokolliert Abschluss, Server protokolliert Status-Update. Differenz ≤2 s.'
  },
  'NFA-06': {
    verification: 'Festes Auftragsset mit einem dann zwei Druckern abarbeiten. Dispatcher loggt Durchsatz; relative Steigerung ≥60 % erwartet.'
  },
  'NFA-07': {
    verification: 'Spring-Boot-Startup-Logs geben Startzeit aus. Test-Client sendet Dummy-Request unmittelbar nach Prozessstart, misst Zeit bis zur ersten erfolgreichen Antwort.'
  }
};

async function refresh() {
  try {
    const [health, nfaStats] = await Promise.all([
      fetchJson(API.health),
      fetchJson(API.nfaStats)
    ]);

    document.getElementById('health').textContent =
      `${health.status} • ${new Date(health.time).toLocaleTimeString()} • strategy=${health.dispatchStrategy}`;

    renderSummary(nfaStats);
    renderAllNfas(nfaStats);
  } catch (err) {
    document.getElementById('health').textContent = `ERROR: ${err.message}`;
    showNotice(`Refresh error: ${err.message}`, true);
    console.error(err);
  }
}

function renderSummary(stats) {
  const nfas = ['nfa01','nfa02','nfa03','nfa04','nfa05','nfa06','nfa07'].map(k => stats[k]).filter(Boolean);
  const passed = nfas.filter(n => n.passed).length;
  const failed = nfas.filter(n => !n.passed).length;
  document.getElementById('summary-total').textContent = stats.totalJobs ?? '–';
  document.getElementById('summary-passed').textContent = `${passed} / ${nfas.length}`;
  document.getElementById('summary-failed').textContent = failed;
  document.getElementById('summary-time').textContent = stats.computedAt
    ? new Date(stats.computedAt).toLocaleTimeString()
    : '–';
}

function renderAllNfas(stats) {
  const map = {
    'nfa-01-card': stats.nfa01,
    'nfa-02-card': stats.nfa02,
    'nfa-03-card': stats.nfa03,
    'nfa-04-card': stats.nfa04,
    'nfa-05-card': stats.nfa05,
    'nfa-06-card': stats.nfa06,
    'nfa-07-card': stats.nfa07
  };
  Object.entries(map).forEach(([cardId, nfa]) => {
    const card = document.getElementById(cardId);
    if (card && nfa) renderNfaCard(card, nfa);
  });
}

function renderNfaCard(card, nfa) {
  const passed = nfa.passed;
  const badgeClass = passed ? 'badge-pass' : 'badge-fail';
  const badgeText = passed ? '✓ PASS' : '✗ FAIL';
  const statusClass = passed ? 'status-stable' : 'status-degraded';

  card.className = `pane nfa-card ${passed ? 'nfa-pass' : 'nfa-fail'}`;
  card.innerHTML = '';

  // Header row
  const header = el('div', 'nfa-header');
  const title = el('span', 'nfa-title');
  title.textContent = nfa.id;
  const badge = el('span', badgeClass);
  badge.textContent = badgeText;
  header.appendChild(title);
  header.appendChild(badge);
  card.appendChild(header);

  // Description
  const desc = el('p', 'nfa-desc');
  desc.textContent = nfa.description;
  card.appendChild(desc);

  // Value vs threshold
  const metric = el('div', 'nfa-metric');
  const valueStr = typeof nfa.value === 'number' ? nfa.value.toLocaleString(undefined, { maximumFractionDigits: 2 }) : nfa.value;
  metric.innerHTML =
    `<span class="${statusClass} nfa-value">${valueStr} ${nfa.unit}</span>` +
    `<span class="nfa-threshold muted"> threshold: ${nfa.threshold} ${nfa.unit}</span>`;
  card.appendChild(metric);

  // Measurement label
  const meas = el('p', 'muted nfa-measurement');
  meas.textContent = `Measurement: ${nfa.measurement}`;
  card.appendChild(meas);

  // Sample count
  const samples = el('p', 'muted');
  samples.textContent = `Samples: ${nfa.sampleCount}`;
  card.appendChild(samples);

  // Extra numeric fields (maxMs, avgMs, speedupFactor, etc.)
  const extras = ['maxMs', 'avgMs', 'speedupFactor', 'activePrinters'];
  extras.forEach(key => {
    if (nfa[key] !== undefined) {
      const p = el('p', 'muted');
      p.textContent = `${key}: ${nfa[key]}`;
      card.appendChild(p);
    }
  });

  // Printer breakdown for NFA-06
  if (nfa.completedByPrinter && Object.keys(nfa.completedByPrinter).length > 0) {
    const breakdown = el('div', 'muted');
    const entries = Object.entries(nfa.completedByPrinter)
      .map(([id, cnt]) => `${id}: ${cnt}`)
      .join(', ');
    breakdown.textContent = `Completed/5min per printer: ${entries}`;
    card.appendChild(breakdown);
  }

  // Note
  if (nfa.note) {
    const note = el('p', 'nfa-note');
    note.textContent = `ℹ ${nfa.note}`;
    card.appendChild(note);
  }

  // Docs verification description
  const docKey = nfa.id;
  if (NFA_DOCS[docKey]) {
    const docDiv = el('div', 'nfa-doc');
    const toggle = el('button', 'btn-doc-toggle');
    toggle.textContent = 'Show verification method';
    const docText = el('p', 'muted nfa-doc-text hidden');
    docText.textContent = NFA_DOCS[docKey].verification;
    toggle.addEventListener('click', () => {
      const hidden = docText.classList.toggle('hidden');
      toggle.textContent = hidden ? 'Show verification method' : 'Hide verification method';
    });
    docDiv.appendChild(toggle);
    docDiv.appendChild(docText);
    card.appendChild(docDiv);
  }
}

refresh();
setInterval(refresh, 5000);
