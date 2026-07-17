package com.jw.ignition.license.page;

/** Render a simple HTML page for module licensing
 *  that is reusable for every module */

public final class LicensePageRenderer {
    public String render(String moduleName) {
        String title = escapeHtml(moduleName == null || moduleName.isBlank() ? "Module License" : moduleName + " License");
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              <style>
                :root {
                  color-scheme: light;
                  --bg: #f6f7f9;
                  --panel: #ffffff;
                  --text: #17202a;
                  --muted: #667085;
                  --border: #d8dde6;
                  --accent: #2563eb;
                  --accent-strong: #1d4ed8;
                  --danger: #b42318;
                  --danger-strong: #912018;
                  --success: #067647;
                  --warning: #b54708;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  background: var(--bg);
                  color: var(--text);
                  font-family: Arial, Helvetica, sans-serif;
                  font-size: 14px;
                  line-height: 1.45;
                }
                main {
                  width: min(1120px, calc(100%% - 40px));
                  margin: 0 auto;
                  padding: 32px 0 48px;
                }
                header {
                  display: flex;
                  align-items: flex-start;
                  justify-content: space-between;
                  gap: 16px;
                  margin-bottom: 20px;
                }
                h1 {
                  margin: 0;
                  font-size: 24px;
                  font-weight: 700;
                  letter-spacing: 0;
                }
                .subtitle {
                  margin: 6px 0 0;
                  color: var(--muted);
                }
                .actions {
                  display: flex;
                  gap: 8px;
                  flex-wrap: wrap;
                  justify-content: flex-end;
                }
                button, .file-label {
                  border: 1px solid var(--border);
                  background: var(--panel);
                  color: var(--text);
                  min-height: 36px;
                  padding: 8px 12px;
                  border-radius: 6px;
                  font: inherit;
                  cursor: pointer;
                }
                button.primary {
                  border-color: var(--accent);
                  background: var(--accent);
                  color: white;
                }
                button.danger {
                  border-color: var(--danger);
                  background: var(--danger);
                  color: white;
                }
                button:hover, .file-label:hover { border-color: var(--accent); }
                button.primary:hover { background: var(--accent-strong); }
                button.danger:hover {
                  border-color: var(--danger-strong);
                  background: var(--danger-strong);
                }
                button:disabled {
                  cursor: not-allowed;
                  opacity: 0.6;
                }
                input[type="file"] {
                  position: absolute;
                  width: 1px;
                  height: 1px;
                  opacity: 0;
                  pointer-events: none;
                }
                .grid {
                  display: grid;
                  grid-template-columns: 2fr 1fr;
                  gap: 16px;
                }
                .panel {
                  background: var(--panel);
                  border: 1px solid var(--border);
                  border-radius: 8px;
                  padding: 18px;
                }
                .panel h2 {
                  margin: 0 0 14px;
                  font-size: 16px;
                  letter-spacing: 0;
                }
                .status-row {
                  display: flex;
                  align-items: center;
                  gap: 10px;
                  margin-bottom: 16px;
                }
                .badge {
                  display: inline-flex;
                  align-items: center;
                  min-height: 26px;
                  border-radius: 999px;
                  padding: 4px 10px;
                  font-weight: 700;
                  border: 1px solid var(--border);
                  background: #f8fafc;
                  color: var(--muted);
                }
                .badge.good {
                  border-color: #abefc6;
                  background: #ecfdf3;
                  color: var(--success);
                }
                .badge.bad {
                  border-color: #fecdca;
                  background: #fef3f2;
                  color: var(--danger);
                }
                .badge.warn {
                  border-color: #fedf89;
                  background: #fffaeb;
                  color: var(--warning);
                }
                dl {
                  display: grid;
                  grid-template-columns: 180px minmax(0, 1fr);
                  gap: 10px 14px;
                  margin: 0;
                }
                dt {
                  color: var(--muted);
                }
                dd {
                  margin: 0;
                  min-width: 0;
                  overflow-wrap: anywhere;
                  font-weight: 600;
                }
                .score {
                  height: 10px;
                  background: #eef2f7;
                  border-radius: 999px;
                  overflow: hidden;
                  margin-top: 10px;
                }
                .score-fill {
                  height: 100%%;
                  width: 0%%;
                  background: var(--accent);
                }
                .file-row {
                  display: flex;
                  gap: 8px;
                  align-items: center;
                  flex-wrap: wrap;
                }
                .file-name {
                  color: var(--muted);
                  min-width: 0;
                  overflow-wrap: anywhere;
                }
                .messages {
                  margin-top: 14px;
                  display: grid;
                  gap: 8px;
                }
                .message {
                  border: 1px solid var(--border);
                  border-radius: 6px;
                  padding: 10px;
                  background: #f8fafc;
                  color: var(--muted);
                  overflow-wrap: anywhere;
                }
                .message.error {
                  border-color: #fecdca;
                  background: #fef3f2;
                  color: var(--danger);
                }
                .message.warning {
                  border-color: #fedf89;
                  background: #fffaeb;
                  color: var(--warning);
                }
                table {
                  width: 100%%;
                  border-collapse: collapse;
                }
                th, td {
                  border-bottom: 1px solid var(--border);
                  padding: 8px;
                  text-align: left;
                  vertical-align: top;
                  overflow-wrap: anywhere;
                }
                th {
                  color: var(--muted);
                  font-weight: 700;
                  background: #f8fafc;
                }
                @media (max-width: 820px) {
                  main { width: min(100%% - 24px, 1120px); padding-top: 20px; }
                  header { display: block; }
                  .actions { justify-content: flex-start; margin-top: 14px; }
                  .grid { grid-template-columns: 1fr; }
                  dl { grid-template-columns: 1fr; gap: 4px 0; }
                  dd { margin-bottom: 8px; }
                }
              </style>
            </head>
            <body>
              <main>
                <header>
                  <div>
                    <h1>%s</h1>
                    <p class="subtitle">Gateway module license management</p>
                  </div>
                  <div class="actions">
                    <button id="refresh" type="button">Refresh</button>
                    <button id="revalidate" class="primary" type="button">Revalidate</button>
                    <button id="download" type="button">Download Machine Information File</button>
                    <button id="backToIgnition" class="danger" type="button">Back to Ignition</button>
                  </div>
                </header>

                <section class="grid">
                  <div class="panel">
                    <h2>License Status</h2>
                    <div class="status-row">
                      <span id="statusBadge" class="badge">Loading</span>
                      <span id="statusMessage" class="subtitle">Reading license status...</span>
                    </div>
                    <dl>
                      <dt>License ID</dt><dd id="licenseId">-</dd>
                      <dt>Client</dt><dd id="client">-</dd>
                      <dt>Module ID</dt><dd id="moduleId">-</dd>
                      <dt>Features</dt><dd id="features">-</dd>
                      <dt>Expires At</dt><dd id="expiresAt">-</dd>
                      <dt>Verified At</dt><dd id="verifiedAt">-</dd>
                      <dt>License File</dt><dd id="licensePath">-</dd>
                      <dt>Matched Identifiers</dt><dd id="matchText">-</dd>
                    </dl>
                    <div class="score" aria-hidden="true"><div id="scoreFill" class="score-fill"></div></div>
                    <div id="messages" class="messages"></div>
                  </div>

                  <div class="panel">
                    <h2>Upload License</h2>
                    <form id="uploadForm">
                      <div class="file-row">
                        <label class="file-label" for="licenseFile">Choose File</label>
                        <input id="licenseFile" name="file" type="file" accept=".json,application/json">
                        <span id="fileName" class="file-name">No file selected</span>
                      </div>
                      <div class="actions" style="justify-content:flex-start;margin-top:14px">
                        <button id="upload" class="primary" type="submit">Upload</button>
                      </div>
                    </form>
                  </div>
                </section>

                <section class="panel" style="margin-top:16px">
                  <h2>Identifier Match Detail</h2>
                  <table>
                    <thead>
                      <tr>
                        <th>Identifier</th>
                        <th>Current Available</th>
                        <th>Matched</th>
                      </tr>
                    </thead>
                    <tbody id="identifierRows">
                      <tr><td colspan="3">No identifier data available.</td></tr>
                    </tbody>
                  </table>
                </section>
              </main>
              <script>
                const endpoints = {
                  status: new URL('status', window.location.href),
                  machineRequest: new URL('machine-request', window.location.href),
                  upload: new URL('upload', window.location.href),
                  revalidate: new URL('revalidate', window.location.href),
                  session: new URL('/data/app/session', window.location.origin)
                };
                let csrfToken = null;

                const el = (id) => document.getElementById(id);
                const text = (id, value) => { el(id).textContent = value || '-'; };
                const pct = (value) => `${Math.max(0, Math.min(100, value)).toFixed(0)}%%`;

                async function csrf() {
                  if (csrfToken) return csrfToken;
                  const response = await fetch(endpoints.session, { credentials: 'include' });
                  if (!response.ok) return null;
                  const payload = await response.json();
                  csrfToken = payload.csrfToken || payload.data?.csrfToken || null;
                  return csrfToken;
                }

                async function request(url, options = {}) {
                  const headers = new Headers(options.headers || {});
                  if ((options.method || 'GET').toUpperCase() !== 'GET') {
                    const token = await csrf();
                    if (token) headers.set('X-CSRF-Token', token);
                  }
                  const response = await fetch(url, { credentials: 'include', ...options, headers });
                  const contentType = response.headers.get('content-type') || '';
                  const body = contentType.includes('application/json') ? await response.json() : await response.text();
                  if (!response.ok) {
                    const message = body?.error?.message || body?.message || response.statusText || 'Request failed.';
                    throw new Error(message);
                  }
                  return body;
                }

                async function loadStatus() {
                  clearMessages();
                  const payload = await request(endpoints.status);
                  renderStatus(payload.data || payload);
                }

                async function revalidate() {
                  setBusy(true);
                  try {
                    const payload = await request(endpoints.revalidate, { method: 'POST' });
                    renderStatus(payload.data || payload);
                    addMessage('License revalidated.', 'info');
                  } finally {
                    setBusy(false);
                  }
                }

                async function upload(event) {
                  event.preventDefault();
                  const file = el('licenseFile').files[0];
                  if (!file) {
                    addMessage('Choose a license file first.', 'warning');
                    return;
                  }
                  const formData = new FormData();
                  formData.append('file', file);
                  setBusy(true);
                  try {
                    const payload = await request(endpoints.upload, { method: 'POST', body: formData });
                    renderStatus(payload.data || payload);
                    addMessage('License uploaded and activated.', 'info');
                  } finally {
                    setBusy(false);
                  }
                }

                function renderStatus(status) {
                  const badge = el('statusBadge');
                  badge.textContent = status.code || 'UNKNOWN';
                  badge.className = `badge ${status.licensed ? 'good' : status.code === 'UNVERIFIED' ? 'warn' : 'bad'}`;
                  text('statusMessage', status.message);
                  text('licenseId', status.licenseId);
                  text('client', status.client);
                  text('moduleId', status.moduleId);
                  text('features', (status.features || []).join(', ') || '-');
                  text('expiresAt', status.expiresAt || 'Lifetime');
                  text('verifiedAt', status.verifiedAt);
                  text('licensePath', status.licensePath);
                  const matched = status.matchedIdentifiers ?? 0;
                  const required = status.requiredMatchedIdentifiers ?? 0;
                  const total = status.licenseIdentifierCount ?? 0;
                  const ratio = Number(status.matchRatio || 0) * 100;
                  text('matchText', total ? `${matched}/${total} matched, ${required} required (${ratio.toFixed(1)}%%)` : '-');
                  el('scoreFill').style.width = pct(ratio);
                  renderMessages(status);
                  renderIdentifiers(status.identifierMatches || {});
                }

                function renderMessages(status) {
                  clearMessages();
                  (status.errors || []).forEach((message) => addMessage(message, 'error'));
                  (status.warnings || []).forEach((message) => addMessage(message, 'warning'));
                }

                function renderIdentifiers(matches) {
                  const rows = el('identifierRows');
                  rows.innerHTML = '';
                  const entries = Object.entries(matches);
                  if (!entries.length) {
                    rows.innerHTML = '<tr><td colspan="3">No identifier data available.</td></tr>';
                    return;
                  }
                  for (const [key, detail] of entries) {
                    const row = document.createElement('tr');
                    row.innerHTML = `<td></td><td></td><td></td>`;
                    row.children[0].textContent = key;
                    row.children[1].textContent = detail.currentAvailable ? 'Yes' : 'No';
                    row.children[2].textContent = detail.matched ? 'Yes' : 'No';
                    rows.appendChild(row);
                  }
                }

                function addMessage(message, type) {
                  const item = document.createElement('div');
                  item.className = `message ${type === 'error' ? 'error' : type === 'warning' ? 'warning' : ''}`;
                  item.textContent = message;
                  el('messages').appendChild(item);
                }

                function clearMessages() {
                  el('messages').innerHTML = '';
                }

                function setBusy(busy) {
                  ['refresh', 'revalidate', 'download', 'upload'].forEach((id) => el(id).disabled = busy);
                }

                function backToIgnition() {
                  const fallback = new URL('/web/home', window.location.origin).href;
                  if (document.referrer) {
                    try {
                      const referrer = new URL(document.referrer);
                      if (referrer.origin === window.location.origin && referrer.href !== window.location.href) {
                        window.location.assign(referrer.href);
                        return;
                      }
                    } catch (ignored) {
                      // Fall through to the Gateway home page.
                    }
                  }
                  window.location.assign(fallback);
                }

                el('refresh').addEventListener('click', () => loadStatus().catch((error) => addMessage(error.message, 'error')));
                el('revalidate').addEventListener('click', () => revalidate().catch((error) => addMessage(error.message, 'error')));
                el('download').addEventListener('click', () => window.location.href = endpoints.machineRequest.href);
                el('backToIgnition').addEventListener('click', backToIgnition);
                el('uploadForm').addEventListener('submit', (event) => upload(event).catch((error) => addMessage(error.message, 'error')));
                el('licenseFile').addEventListener('change', () => {
                  const file = el('licenseFile').files[0];
                  el('fileName').textContent = file ? file.name : 'No file selected';
                });

                loadStatus().catch((error) => addMessage(error.message, 'error'));
              </script>
            </body>
            </html>
            """.formatted(title, title);
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
