const API = {
  health: '/api/admin/health',
  printers: '/api/admin/printers',
  queue: '/api/admin/queue',
  dispatchPolicy: '/api/admin/dispatch-policy',
  monitoring: '/api/admin/monitoring',
  bulkJobs: '/api/admin/jobs/bulk',
  jobs: '/api/jobs',
  wipeLogs: '/api/admin/logs/wipe'
};

async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }
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

    document.getElementById('health').textContent =
      `${health.status} • ${new Date(health.time).toLocaleTimeString()} • strategy=${health.dispatchStrategy}`;
    document.getElementById('printer-count').textContent = `(${printers.length})`;
    document.getElementById('queue-size').textContent = `(${health.queueSize})`;

    renderDispatchPolicy(dispatchPolicy);
    renderMonitoring(monitoring);
    renderPrinters(printers);
    renderQueue(queue);
    renderJobs(jobs);
  } catch (err) {
    document.getElementById('health').textContent = `ERROR: ${err.message}`;
    console.error(err);
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
  available.forEach((entry) => {
    const option = el('option');
    option.value = entry.key;
    option.textContent = `${entry.label} (${entry.key})`;
    select.appendChild(option);
  });
  select.value = available.some((s) => s.key === selectedBefore) ? selectedBefore : strategy;

  const pp = policy.printerPolicy || {};
  printerPolicy.textContent =
    `Profile policy: ${pp.profileMatching || '-'} • Priority policy: ${pp.priorityHandling || '-'}`;
}

function renderPrinters(printers) {
  const container = document.getElementById('printers');
  container.innerHTML = '';
  if (!printers.length) {
    container.textContent = 'No printers registered';
    return;
  }

  printers.forEach((printer) => {
    const card = el('div', 'printer');
    const headline = el('div', 'row');
    headline.innerHTML = `<strong>${printer.name}</strong><span class="muted">(${printer.id})</span>`;
    card.appendChild(headline);

    const stateClass = printer.recoveryState === 'STABLE'
      ? 'status-stable'
      : printer.recoveryState === 'DEGRADED'
        ? 'status-degraded'
        : 'status-recovering';
    const lastSeen = printer.lastSeenAt ? new Date(printer.lastSeenAt).toLocaleTimeString() : '-';
    const info = el('div', 'muted');
    info.innerHTML =
      `${printer.host || 'localhost'}:${printer.port || 0} • online=${printer.online} • connected=${printer.connected} • active=${printer.activeAssignments} • simulator=${printer.simulatorRunning ? 'yes' : 'no'}<br><span class="${stateClass}">recovery=${printer.recoveryState || '-'}</span> • lastSeen=${lastSeen}`;
    card.appendChild(info);

    if (printer.latestEvent) {
      const latest = el('div', 'muted');
      latest.textContent =
        `Latest event: ${printer.latestEvent.type || '-'} • ${printer.latestEvent.message || '-'} • ${printer.latestEvent.createdAt ? new Date(printer.latestEvent.createdAt).toLocaleTimeString() : '-'}`;
      card.appendChild(latest);
    }

    const profiles = el('div', 'muted');
    profiles.textContent =
      `Profiles: ${printer.supportedProfiles && printer.supportedProfiles.length ? printer.supportedProfiles.map((x) => x.id || x.name).join(', ') : 'any'}`;
    card.appendChild(profiles);

    const actions = el('div', 'actions');
    const toggle = el('button');
    toggle.textContent = printer.online ? 'Set Offline' : 'Set Online';
    toggle.onclick = async () => {
      try {
        const res = await fetch(`${API.printers}/${printer.id}/online?online=${!printer.online}`, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        if (printer.online) {
          await fetch(`${API.printers}/${printer.id}/disconnect`, { method: 'POST' });
          await fetch(`${API.printers}/${printer.id}/simulator/stop`, { method: 'POST' });
        }
        await refresh();
      } catch (err) {
        showNotice(`Failed to toggle printer ${printer.id}: ${err.message}`, true);
      }
    };
    actions.appendChild(toggle);

    const simBtn = el('button');
    simBtn.textContent = printer.simulatorRunning ? 'Stop Simulator' : 'Start Simulator';
    simBtn.onclick = async () => {
      try {
        const path = printer.simulatorRunning ? 'simulator/stop' : 'simulator/start';
        const res = await fetch(`${API.printers}/${printer.id}/${path}`, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        await refresh();
      } catch (err) {
        showNotice(`Simulator action failed for ${printer.id}: ${err.message}`, true);
      }
    };
    actions.appendChild(simBtn);

    const removeBtn = el('button');
    removeBtn.textContent = 'Remove Printer';
    removeBtn.className = 'danger-button';
    removeBtn.onclick = async () => {
      if (!window.confirm(`Remove printer ${printer.id}? This disconnects it and deletes its registration.`)) {
        return;
      }
      try {
        const res = await fetch(`${API.printers}/${encodeURIComponent(printer.id)}/remove`, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        showNotice(`Printer ${printer.id} removed`);
        await refresh();
      } catch (err) {
        showNotice(`Remove printer failed: ${err.message}`, true);
      }
    };
    actions.appendChild(removeBtn);

    card.appendChild(actions);
    container.appendChild(card);
  });
}

function renderQueue(queue) {
  const container = document.getElementById('queue');
  container.innerHTML = '';
  if (!queue.length) {
    container.textContent = 'Queue is empty';
    return;
  }
  queue.forEach((job) => {
    const row = el('div', 'job-row');
    row.textContent =
      `${job.id} • ${job.fileReference} • priority=${job.priority} • user=${job.userId || '-'} • status=${job.status}`;
    container.appendChild(row);
  });
}

function renderJobs(jobs) {
  const container = document.getElementById('jobs');
  container.innerHTML = '';
  if (!jobs.length) {
    container.textContent = 'No jobs';
    return;
  }

  jobs.slice().reverse().slice(0, 80).forEach((job) => {
    const row = el('div', 'job-row');
    const errorPart = job.errorMessage ? ` • error=${job.errorMessage}` : '';
    row.innerHTML =
      `<strong>${job.id}</strong> • ${job.fileReference} • ${job.status} • user=${job.userId || '-'}${errorPart}<br><small class="muted">created=${new Date(job.createdAt).toLocaleString()} assignedPrinter=${job.assignedPrinterId || '-'}</small>`;
    container.appendChild(row);
  });
}

function renderMonitoring(monitoring) {
  const throughput = monitoring && monitoring.throughput ? monitoring.throughput : {};
  const errors = monitoring && monitoring.errors ? monitoring.errors : {};
  const recovery = monitoring && monitoring.recovery ? monitoring.recovery : {};
  const health = monitoring && monitoring.jobHealth ? monitoring.jobHealth : {};

  document.getElementById('monitoring-throughput').textContent =
    `${throughput.completedPerMinute || 0}/min • ${throughput.completedLast5Min || 0} in last 5m`;
  document.getElementById('monitoring-errors').textContent =
    `failed-rate=${errors.failedTerminalRatePercent || 0}% • printer-failures=${errors.printerFailuresLast15Min || 0} • disconnects=${errors.disconnectsLast15Min || 0}`;
  document.getElementById('monitoring-recovery').textContent =
    `retries=${recovery.recoveriesLast15Min || 0} • queued-for-retry=${recovery.currentlyQueuedForRetry || 0}`;

  const healthContainer = document.getElementById('monitoring-job-health');
  healthContainer.innerHTML = '';
  Object.keys(health).forEach((key) => {
    const row = el('div', 'job-row');
    row.textContent = `${key}: ${health[key]}`;
    healthContainer.appendChild(row);
  });
}

async function addPrinter(form) {
  const data = new FormData(form);
  const id = (data.get('id') || '').toString().trim();
  if (!id) {
    showNotice('Printer ID is required', true);
    return;
  }
  const name = (data.get('name') || id).toString().trim();
  const host = (data.get('host') || '').toString().trim();
  const port = parseInt((data.get('port') || '0').toString(), 10) || 0;
  const profilesCsv = (data.get('profiles') || '').toString().trim();
  const online = !!data.get('online');
  const connect = !!data.get('connect');
  const profiles = profilesCsv
    ? profilesCsv.split(',').map((item) => ({ id: item.trim(), name: item.trim() })).filter((item) => item.id)
    : [];

  try {
    const createRes = await fetch(API.printers, {
      method: 'POST',
      body: JSON.stringify({ id, name, supportedProfiles: profiles, online }),
      headers: { 'Content-Type': 'application/json' }
    });
    if (!createRes.ok) throw new Error(`HTTP ${createRes.status}`);

    if (connect && host && port > 0) {
      const connectRes = await fetch(`${API.printers}/${id}/connect`, {
        method: 'POST',
        body: JSON.stringify({ host, port, name }),
        headers: { 'Content-Type': 'application/json' }
      });
      if (!connectRes.ok) throw new Error(`HTTP ${connectRes.status}`);
    }

    form.reset();
    showNotice(`Printer ${id} created`);
    await refresh();
  } catch (err) {
    showNotice(`Failed to add printer: ${err.message}`, true);
  }
}

async function createSingleJob(form) {
  const data = new FormData(form);
  const fileReference = (data.get('fileReference') || '').toString().trim();
  if (!fileReference) {
    showNotice('fileReference is required', true);
    return;
  }

  const profileId = (data.get('profileId') || '').toString().trim();
  const priority = parseInt((data.get('priority') || '1').toString(), 10) || 1;
  const userId = (data.get('userId') || '').toString().trim() || 'admin';
  const profile = profileId ? { id: profileId } : {};

  try {
    const res = await fetch(API.jobs, {
      method: 'POST',
      body: JSON.stringify({ fileReference, profile, priority, userId }),
      headers: { 'Content-Type': 'application/json' }
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    form.reset();
    showNotice(`Job created: ${fileReference}`);
    await refresh();
  } catch (err) {
    showNotice(`Create job failed: ${err.message}`, true);
  }
}

async function createBulkJobs(form) {
  const data = new FormData(form);
  const count = parseInt((data.get('count') || '0').toString(), 10);
  if (!Number.isFinite(count) || count <= 0) {
    showNotice('Bulk count must be a positive integer', true);
    return;
  }

  const body = {
    count,
    filePrefix: (data.get('filePrefix') || '').toString().trim(),
    startIndex: parseInt((data.get('startIndex') || '1').toString(), 10) || 1,
    profileId: (data.get('profileId') || '').toString().trim(),
    priority: parseInt((data.get('priority') || '1').toString(), 10) || 1,
    userId: (data.get('userId') || '').toString().trim()
  };

  try {
    const res = await fetchJson(API.bulkJobs, {
      method: 'POST',
      body: JSON.stringify(body),
      headers: { 'Content-Type': 'application/json' }
    });
    showNotice(`Bulk created ${res.created}/${res.requested} jobs`);
    await refresh();
  } catch (err) {
    showNotice(`Bulk create failed: ${err.message}`, true);
  }
}

function registerHandlers() {
  const addPrinterForm = document.getElementById('add-printer-form');
  if (addPrinterForm) {
    addPrinterForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      await addPrinter(event.target);
    });
  }

  const createJobForm = document.getElementById('create-job-form');
  if (createJobForm) {
    createJobForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      await createSingleJob(event.target);
    });
  }

  const bulkCreateForm = document.getElementById('bulk-create-form');
  if (bulkCreateForm) {
    bulkCreateForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      await createBulkJobs(event.target);
    });
  }

  const wipeLogsBtn = document.getElementById('wipe-logs-button');
  if (wipeLogsBtn) {
    wipeLogsBtn.addEventListener('click', async () => {
      if (!window.confirm('Clear all recorded server logs/events?')) {
        return;
      }
      try {
        const res = await fetch(API.wipeLogs, { method: 'POST' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        showNotice('Server event log cleared');
        await refresh();
      } catch (err) {
        showNotice(`Wipe logs failed: ${err.message}`, true);
      }
    });
  }

  const dispatchPolicyForm = document.getElementById('dispatch-policy-form');
  if (dispatchPolicyForm) {
    dispatchPolicyForm.addEventListener('submit', async (event) => {
      event.preventDefault();
      const data = new FormData(event.target);
      const strategy = (data.get('strategy') || '').toString().trim();
      if (!strategy) {
        showNotice('Dispatch strategy is required', true);
        return;
      }
      try {
        const res = await fetch(API.dispatchPolicy, {
          method: 'PUT',
          body: JSON.stringify({ strategy }),
          headers: { 'Content-Type': 'application/json' }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        showNotice(`Dispatch strategy updated to ${strategy}`);
        await refresh();
      } catch (err) {
        showNotice(`Dispatch policy update failed: ${err.message}`, true);
      }
    });
  }
}

registerHandlers();
refresh();
setInterval(refresh, 3000);
