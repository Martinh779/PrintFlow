const API = {
  health: '/api/admin/health',
  printers: '/api/admin/printers',
  queue: '/api/admin/queue',
  dispatchPolicy: '/api/admin/dispatch-policy',
  monitoring: '/api/admin/monitoring',
  jobs: '/api/jobs'
};

async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return res.json();
}

function el(tag, cls) { const e = document.createElement(tag); if (cls) e.className = cls; return e; }

async function refresh() {
  try {
    const [health, printers, queue, jobs, dispatchPolicy, monitoring] = await Promise.all([
      fetchJson(API.health),
      fetchJson(API.printers),
      fetchJson(API.queue),
      fetchJson(API.jobs),
      fetchJson(API.dispatchPolicy),
      fetchJson(API.monitoring)
    ]);

    document.getElementById('health').textContent = `${health.status} @ ${new Date(health.time).toLocaleTimeString()} • strategy=${health.dispatchStrategy}`;
    document.getElementById('printer-count').textContent = `(${printers.length})`;
    document.getElementById('queue-size').textContent = `(${health.queueSize})`;

    renderDispatchPolicy(dispatchPolicy);
    renderMonitoring(monitoring);
    renderPrinters(printers);
    renderQueue(queue);
    renderJobs(jobs);
  } catch (e) {
    document.getElementById('health').textContent = `ERROR: ${e.message}`;
    console.error(e);
  }
}

function renderDispatchPolicy(policy) {
  const current = document.getElementById('dispatch-policy-current');
  const printerPolicy = document.getElementById('dispatch-policy-printer-policy');
  const select = document.getElementById('dispatch-strategy-select');
  if (!current || !printerPolicy || !select) return;

  const strategy = policy.strategy || '-';
  const defaultStrategy = policy.defaultStrategy || '-';
  current.textContent = `Current: ${strategy} • Default: ${defaultStrategy}`;

  const available = Array.isArray(policy.availableStrategies) ? policy.availableStrategies : [];
  const selectedBefore = select.value;
  select.innerHTML = '';
  available.forEach(entry => {
    const option = el('option');
    option.value = entry.key;
    option.textContent = `${entry.label} (${entry.key})`;
    select.appendChild(option);
  });
  select.value = available.some(s => s.key === selectedBefore) ? selectedBefore : strategy;

  const pp = policy.printerPolicy || {};
  printerPolicy.textContent = `Profile policy: ${pp.profileMatching || '-'} • Priority policy: ${pp.priorityHandling || '-'}`;
}

function renderPrinters(printers) {
  const container = document.getElementById('printers');
  container.innerHTML = '';
  if (!printers.length) { container.textContent = 'No printers registered'; return; }
  printers.forEach(p => {
    const card = el('div','printer');
    const h = el('div','row');
    h.innerHTML = `<strong>${p.name}</strong> <span class="muted">(${p.id})</span>`;
    card.appendChild(h);

    const info = el('div','muted');
    const stateClass = p.recoveryState === 'STABLE' ? 'status-stable' : p.recoveryState === 'DEGRADED' ? 'status-degraded' : 'status-recovering';
    const lastSeen = p.lastSeenAt ? new Date(p.lastSeenAt).toLocaleTimeString() : '-';
    info.innerHTML = `${p.host || 'localhost'}:${p.port || 0} • online=${p.online} • connected=${p.connected} • active=${p.activeAssignments} • sim=${p.simulatorRunning ? 'yes' : 'no'}<br><span class="${stateClass}">recovery=${p.recoveryState || '-'}</span> • lastSeen=${lastSeen}`;
    card.appendChild(info);

    if (p.latestEvent) {
      const latest = el('div', 'muted');
      latest.textContent = `Latest event: ${p.latestEvent.type || '-'} • ${p.latestEvent.message || '-'} • ${p.latestEvent.createdAt ? new Date(p.latestEvent.createdAt).toLocaleTimeString() : '-'}`;
      card.appendChild(latest);
    }

    const prof = el('div','profiles');
    prof.textContent = 'Profiles: ' + (p.supportedProfiles && p.supportedProfiles.length ? p.supportedProfiles.map(x=>x.id||x.name).join(', ') : 'any');
    card.appendChild(prof);

    const actions = el('div','actions');

    // toggle online/offline; when setting offline also attempt to disconnect
    const toggle = el('button'); toggle.textContent = p.online ? 'Set offline' : 'Set online';
    toggle.onclick = async () => {
      try {
        await fetch(`${API.printers}/${p.id}/online?online=${!p.online}`, { method: 'POST' });
        // if taking offline, also disconnect any active outgoing connection so dispatcher won't use it
        if (p.online) {
          try { await fetch(`${API.printers}/${p.id}/disconnect`, { method: 'POST' }); } catch(e) { /* ignore */ }
          try { await fetch(`${API.printers}/${p.id}/simulator/stop`, { method: 'POST' }); } catch(e) { /* ignore */ }
        }
        await refresh();
      } catch (e) { alert('Failed: '+e.message); }
    };
    actions.appendChild(toggle);

    // connect button: prompt for host/port
    const connectBtn = el('button'); connectBtn.textContent = 'Connect...';
    connectBtn.onclick = async () => {
      try {
        const host = prompt('Host to connect to', p.host || 'localhost');
        if (!host) return;
        const portStr = prompt('Port', p.port ? String(p.port) : '50000');
        if (!portStr) return;
        const port = parseInt(portStr, 10);
        const body = { host, port, name: p.name };
        const res = await fetch(`${API.printers}/${p.id}/connect`, { method: 'POST', body: JSON.stringify(body), headers: {'Content-Type':'application/json'} });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        await refresh();
      } catch (e) { alert('Connect failed: '+e.message); }
    };
    actions.appendChild(connectBtn);

    // simulator controls
    const simBtn = el('button'); simBtn.textContent = p.simulatorRunning ? 'Stop Simulator' : 'Start Simulator';
    simBtn.onclick = async () => {
      try {
        const path = p.simulatorRunning ? 'simulator/stop' : 'simulator/start';
        const res = await fetch(`${API.printers}/${p.id}/${path}`, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        await refresh();
      } catch (e) { alert('Simulator action failed: '+e.message); }
    };
    actions.appendChild(simBtn);

    // create job: prompt user for fileReference/profile/priority
    const createBtn = el('button'); createBtn.textContent = 'Create Job';
    createBtn.onclick = async () => {
      try {
        const fileReference = prompt('fileReference', `job-${Date.now()}.txt`);
        if (!fileReference) return;
        const defaultProfile = (p.supportedProfiles && p.supportedProfiles.length) ? (p.supportedProfiles[0].id || p.supportedProfiles[0].name) : '';
        const profileId = prompt('profile id (leave empty for any)', defaultProfile);
        const prioStr = prompt('priority', '1'); if (!prioStr) return; const priority = parseInt(prioStr, 10) || 1;
        const userId = prompt('userId', 'admin') || 'admin';

        const profile = profileId ? { id: profileId } : {};
        const body = { fileReference, profile, priority, userId };
        const res = await fetch(API.jobs, { method: 'POST', body: JSON.stringify(body), headers: {'Content-Type':'application/json'} });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        await refresh();
      } catch (e) { alert('Failed to create job: '+e.message); }
    };
    actions.appendChild(createBtn);

    // disconnect button
    const discBtn = el('button'); discBtn.textContent = 'Disconnect';
    discBtn.onclick = async () => {
      try {
        const res = await fetch(`${API.printers}/${p.id}/disconnect`, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        await refresh();
      } catch (e) { alert('Disconnect failed: '+e.message); }
    };
    actions.appendChild(discBtn);

    card.appendChild(actions);

    container.appendChild(card);
  });
}

function renderQueue(queue) {
  const c = document.getElementById('queue');
  c.innerHTML = '';
  if (!queue.length) { c.textContent = 'Queue is empty'; return; }
  queue.forEach(j => {
    const row = el('div','job-row');
    row.textContent = `${j.id} • ${j.fileReference} • priority=${j.priority} • user=${j.userId || '-'} • status=${j.status}`;
    c.appendChild(row);
  });
}

function renderJobs(jobs) {
  const c = document.getElementById('jobs');
  c.innerHTML = '';
  if (!jobs.length) { c.textContent = 'No jobs'; return; }
  jobs.slice().reverse().slice(0,50).forEach(j => {
    const row = el('div','job-row');
    const errorPart = j.errorMessage ? ` • error=${j.errorMessage}` : '';
    row.innerHTML = `<strong>${j.id}</strong> • ${j.fileReference} • ${j.status} • user=${j.userId||'-'}${errorPart}<br><small class="muted">created=${new Date(j.createdAt).toLocaleString()} assignedPrinter=${j.assignedPrinterId||'-'}</small>`;
    c.appendChild(row);
  });
}

function renderMonitoring(monitoring) {
  const throughput = monitoring && monitoring.throughput ? monitoring.throughput : {};
  const errors = monitoring && monitoring.errors ? monitoring.errors : {};
  const recovery = monitoring && monitoring.recovery ? monitoring.recovery : {};
  const health = monitoring && monitoring.jobHealth ? monitoring.jobHealth : {};

  document.getElementById('monitoring-throughput').textContent =
    `Throughput: ${throughput.completedPerMinute || 0}/min (${throughput.completedLast5Min || 0} completed in last 5m)`;
  document.getElementById('monitoring-errors').textContent =
    `Errors: failed-terminal-rate=${errors.failedTerminalRatePercent || 0}% • printer-failures=${errors.printerFailuresLast15Min || 0} • disconnects=${errors.disconnectsLast15Min || 0} (last 15m)`;
  document.getElementById('monitoring-recovery').textContent =
    `Recovery: retries=${recovery.recoveriesLast15Min || 0} • queued-for-retry=${recovery.currentlyQueuedForRetry || 0} (last 15m)`;

  const healthContainer = document.getElementById('monitoring-job-health');
  healthContainer.innerHTML = '';
  Object.keys(health).forEach(key => {
    const row = el('div', 'job-row');
    row.textContent = `${key}: ${health[key]}`;
    healthContainer.appendChild(row);
  });
}

async function addPrinter(form) {
  const data = new FormData(form);
  const id = (data.get('id') || '').toString().trim();
  if (!id) return alert('id required');
  const name = (data.get('name') || id).toString().trim();
  const host = (data.get('host') || '').toString().trim();
  const port = parseInt((data.get('port') || '0').toString(), 10) || 0;
  const profCsv = (data.get('profiles') || '').toString().trim();
  const online = !!data.get('online');
  const connect = !!data.get('connect');
  const profiles = profCsv ? profCsv.split(',').map(s=>({id:s.trim(), name:s.trim()})) : [];
  try {
    const body = { id, name, supportedProfiles: profiles, online };
    const res = await fetch(API.printers, { method: 'POST', body: JSON.stringify(body), headers: {'Content-Type':'application/json'} });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    // if user asked to connect immediately and provided host/port, call connect
    if (connect && host && port > 0) {
      try {
        await fetch(`${API.printers}/${id}/connect`, { method: 'POST', body: JSON.stringify({ host, port, name }), headers: {'Content-Type':'application/json'} });
      } catch (e) { console.warn('connect failed', e); }
    }

    form.reset();
    await refresh();
  } catch (e) { alert('Failed to add printer: '+e.message); }
}

// form handlers
const addPrinterForm = document.getElementById('add-printer-form');
if (addPrinterForm) addPrinterForm.addEventListener('submit', e => { e.preventDefault(); addPrinter(e.target); });

const createJobForm = document.getElementById('create-job-form');
if (createJobForm) createJobForm.addEventListener('submit', async e => {
  e.preventDefault();
  const data = new FormData(e.target);
  const fileReference = (data.get('fileReference') || '').toString().trim();
  if (!fileReference) return alert('fileReference required');
  const profileId = (data.get('profileId') || '').toString().trim();
  const priority = parseInt((data.get('priority') || '1').toString(), 10) || 1;
  const userId = (data.get('userId') || '').toString().trim() || 'admin';
  const profile = profileId ? { id: profileId } : {};
  try {
    const res = await fetch(API.jobs, { method: 'POST', body: JSON.stringify({ fileReference, profile, priority, userId }), headers: {'Content-Type':'application/json'} });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    e.target.reset();
    await refresh();
  } catch (err) { alert('Create job failed: ' + err.message); }
});

const dispatchPolicyForm = document.getElementById('dispatch-policy-form');
if (dispatchPolicyForm) dispatchPolicyForm.addEventListener('submit', async e => {
  e.preventDefault();
  const data = new FormData(e.target);
  const strategy = (data.get('strategy') || '').toString().trim();
  if (!strategy) return alert('strategy required');
  try {
    const res = await fetch(API.dispatchPolicy, {
      method: 'PUT',
      body: JSON.stringify({ strategy }),
      headers: { 'Content-Type': 'application/json' }
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    await refresh();
  } catch (err) {
    alert('Dispatch policy update failed: ' + err.message);
  }
});

// initial load + interval
refresh();
setInterval(refresh, 3000);
