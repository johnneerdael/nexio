package com.nuvio.tv.core.server

object RepositoryWebPage {

    fun getHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>NuvioTV - Manage Repositories</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #0D0D0D;
    color: #FFFFFF;
    min-height: 100vh;
    padding: 16px;
  }
  .header {
    text-align: center;
    padding: 20px 0;
    border-bottom: 1px solid #2D2D2D;
    margin-bottom: 20px;
  }
  .header h1 {
    font-size: 22px;
    font-weight: 600;
    margin-bottom: 4px;
  }
  .header p {
    color: #808080;
    font-size: 14px;
  }
  .add-section {
    background: #1A1A1A;
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 20px;
  }
  .add-section label {
    display: block;
    font-size: 14px;
    font-weight: 600;
    margin-bottom: 8px;
    color: #B3B3B3;
  }
  .add-row {
    display: flex;
    gap: 8px;
  }
  .add-row input {
    flex: 1;
    background: #242424;
    border: 1px solid #3D3D3D;
    border-radius: 8px;
    padding: 12px;
    color: #FFFFFF;
    font-size: 16px;
    outline: none;
  }
  .add-row input:focus {
    border-color: #9E9E9E;
  }
  .add-row input::placeholder {
    color: #606060;
  }
  .btn {
    background: #9E9E9E;
    color: #000000;
    border: none;
    border-radius: 8px;
    padding: 12px 20px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    white-space: nowrap;
    -webkit-tap-highlight-color: transparent;
  }
  .btn:active { opacity: 0.8; }
  .btn-save {
    width: 100%;
    padding: 16px;
    font-size: 16px;
    margin-top: 20px;
  }
  .btn-save:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
  .btn-danger {
    background: transparent;
    color: #CF6679;
    border: 1px solid #CF6679;
    padding: 8px 14px;
    font-size: 13px;
  }
  .section-title {
    font-size: 16px;
    font-weight: 600;
    margin-bottom: 12px;
    color: #B3B3B3;
  }
  .repo-list {
    list-style: none;
  }
  .repo-item {
    background: #1A1A1A;
    border-radius: 12px;
    padding: 14px 16px;
    margin-bottom: 8px;
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .repo-info {
    flex: 1;
    min-width: 0;
  }
  .repo-name {
    font-size: 15px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .repo-url {
    font-size: 12px;
    color: #808080;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 2px;
  }
  .repo-desc {
    font-size: 13px;
    color: #B3B3B3;
    margin-top: 2px;
  }
  .repo-actions {
    flex-shrink: 0;
  }
  .empty {
    text-align: center;
    color: #606060;
    padding: 40px 0;
    font-size: 14px;
  }
  .status-bar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    background: #1A1A1A;
    border-top: 1px solid #2D2D2D;
    padding: 16px;
    text-align: center;
    font-size: 14px;
    z-index: 200;
    display: none;
  }
  .status-bar.visible { display: block; }
  .status-bar.pending { color: #FFD700; }
  .status-bar.confirmed { color: #4CAF50; }
  .status-bar.rejected { color: #CF6679; }
  .status-bar.error { color: #CF6679; }
  .loading-spinner {
    display: inline-block;
    width: 16px;
    height: 16px;
    border: 2px solid #FFD700;
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    vertical-align: middle;
    margin-right: 8px;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .badge-new {
    display: inline-block;
    background: #4CAF50;
    color: #000;
    font-size: 10px;
    font-weight: 700;
    padding: 2px 6px;
    border-radius: 4px;
    margin-left: 6px;
    vertical-align: middle;
  }
</style>
</head>
<body>
<div class="header">
  <h1>NuvioTV</h1>
  <p>Manage your repositories</p>
</div>

<div class="add-section">
  <label>Add repository by URL</label>
  <div class="add-row">
    <input type="url" id="repoUrl" placeholder="https://example.com/manifest.json" autocomplete="off" autocapitalize="off" spellcheck="false">
    <button class="btn" id="addBtn" onclick="addRepo()">Add</button>
  </div>
  <div id="addError" style="color:#CF6679;font-size:13px;margin-top:8px;display:none"></div>
</div>

<div class="section-title">Installed repositories</div>
<ul class="repo-list" id="repoList"></ul>
<div class="empty" id="emptyState" style="display:none">No repositories installed</div>

<button class="btn btn-save" id="saveBtn" onclick="saveChanges()">Save changes</button>

<div class="status-bar" id="statusBar"></div>

<script>
let repos = [];
let originalRepos = [];

async function loadRepos() {
  try {
    const res = await fetch('/api/repositories');
    repos = await res.json();
    originalRepos = JSON.parse(JSON.stringify(repos));
    renderList();
  } catch (e) {
    showStatus('Failed to load repositories', 'error');
  }
}

function renderList() {
  const list = document.getElementById('repoList');
  const empty = document.getElementById('emptyState');
  list.innerHTML = '';
  if (repos.length === 0) {
    empty.style.display = 'block';
    return;
  }
  empty.style.display = 'none';
  repos.forEach((repo, i) => {
    const li = document.createElement('li');
    li.className = 'repo-item';
    li.innerHTML =
      '<div class="repo-info">' +
        '<div class="repo-name">' + escapeHtml(repo.name || repo.url) +
          (repo.isNew ? '<span class="badge-new">NEW</span>' : '') +
        '</div>' +
        (repo.description ? '<div class="repo-desc">' + escapeHtml(repo.description) + '</div>' : '') +
        '<div class="repo-url">' + escapeHtml(repo.url) + '</div>' +
      '</div>' +
      '<div class="repo-actions">' +
        '<button class="btn btn-danger" onclick="removeRepo(' + i + ')">Remove</button>' +
      '</div>';
    list.appendChild(li);
  });
}

async function addRepo() {
  const input = document.getElementById('repoUrl');
  const addBtn = document.getElementById('addBtn');
  const errorEl = document.getElementById('addError');
  let url = input.value.trim();
  if (!url) return;

  if (url.startsWith('stremio://')) {
    url = url.replace(/^stremio:\/\//, 'https://');
  }
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'https://' + url;
  }
  url = url.replace(/\/+$/, '');

  if (repos.some(r => r.url === url)) {
    errorEl.textContent = 'This repository is already in the list';
    errorEl.style.display = 'block';
    setTimeout(() => { errorEl.style.display = 'none'; }, 3000);
    return;
  }

  // Validate and fetch repo info from the TV
  addBtn.disabled = true;
  addBtn.textContent = '...';
  errorEl.style.display = 'none';

  try {
    const res = await fetch('/api/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: url })
    });
    const data = await res.json();

    if (data.error) {
      errorEl.textContent = data.error;
      errorEl.style.display = 'block';
      setTimeout(() => { errorEl.style.display = 'none'; }, 4000);
    } else {
      repos.push({ url: data.url, name: data.name || url, description: data.description, isNew: true });
      input.value = '';
      renderList();
    }
  } catch (e) {
    errorEl.textContent = 'Failed to validate repository';
    errorEl.style.display = 'block';
    setTimeout(() => { errorEl.style.display = 'none'; }, 4000);
  }

  addBtn.disabled = false;
  addBtn.textContent = 'Add';
}

function removeRepo(index) {
  repos.splice(index, 1);
  renderList();
}

async function saveChanges() {
  const saveBtn = document.getElementById('saveBtn');
  saveBtn.disabled = true;

  const urls = repos.map(r => r.url);
  try {
    const res = await fetch('/api/repositories', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ urls: urls })
    });
    const data = await res.json();

    if (data.status === 'pending_confirmation') {
      showStatus('<span class="loading-spinner"></span>Please confirm the changes on your TV', 'pending');
      pollStatus(data.id);
    } else if (data.error) {
      showStatus(data.error, 'error');
      saveBtn.disabled = false;
    }
  } catch (e) {
    showStatus('Failed to save. Check your connection.', 'error');
    saveBtn.disabled = false;
  }
}

async function pollStatus(changeId) {
  const poll = async () => {
    try {
      const res = await fetch('/api/status/' + changeId);
      const data = await res.json();
      if (data.status === 'confirmed') {
        showStatus('Changes applied successfully!', 'confirmed');
        setTimeout(() => {
          loadRepos();
          hideStatus();
          document.getElementById('saveBtn').disabled = false;
        }, 2000);
      } else if (data.status === 'rejected') {
        showStatus('Changes were rejected on the TV', 'rejected');
        setTimeout(() => {
          repos = JSON.parse(JSON.stringify(originalRepos));
          renderList();
          hideStatus();
          document.getElementById('saveBtn').disabled = false;
        }, 2000);
      } else {
        setTimeout(poll, 2000);
      }
    } catch (e) {
      setTimeout(poll, 3000);
    }
  };
  poll();
}

function showStatus(msg, type) {
  const bar = document.getElementById('statusBar');
  bar.innerHTML = msg;
  bar.className = 'status-bar visible ' + type;
}

function hideStatus() {
  document.getElementById('statusBar').className = 'status-bar';
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

document.getElementById('repoUrl').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') addRepo();
});

loadRepos();
</script>
</body>
</html>
""".trimIndent()
}
