package com.nuvio.tv.core.server

object AddonWebPage {

    fun getHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>NuvioTV - Manage Addons</title>
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
  .addon-list {
    list-style: none;
  }
  .addon-item {
    background: #1A1A1A;
    border-radius: 12px;
    padding: 14px 16px;
    margin-bottom: 8px;
    display: flex;
    align-items: center;
    gap: 12px;
    touch-action: none;
    user-select: none;
    -webkit-user-select: none;
    transition: background 0.15s, transform 0.15s, box-shadow 0.15s;
  }
  .addon-item.dragging {
    background: #2D2D2D;
    box-shadow: 0 4px 20px rgba(0,0,0,0.5);
    transform: scale(1.02);
    z-index: 100;
  }
  .addon-item.drag-over {
    border-top: 2px solid #9E9E9E;
  }
  .drag-handle {
    color: #606060;
    font-size: 20px;
    cursor: grab;
    padding: 4px;
    flex-shrink: 0;
  }
  .addon-info {
    flex: 1;
    min-width: 0;
  }
  .addon-name {
    font-size: 15px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .addon-url {
    font-size: 12px;
    color: #808080;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 2px;
  }
  .addon-desc {
    font-size: 13px;
    color: #B3B3B3;
    margin-top: 2px;
  }
  .addon-actions {
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
  <p>Manage your addons</p>
</div>

<div class="add-section">
  <label>Add addon by URL</label>
  <div class="add-row">
    <input type="url" id="addonUrl" placeholder="https://example.com/manifest.json" autocomplete="off" autocapitalize="off" spellcheck="false">
    <button class="btn" id="addBtn" onclick="addAddon()">Add</button>
  </div>
  <div id="addError" style="color:#CF6679;font-size:13px;margin-top:8px;display:none"></div>
</div>

<div class="section-title">Installed addons</div>
<ul class="addon-list" id="addonList"></ul>
<div class="empty" id="emptyState" style="display:none">No addons installed</div>

<button class="btn btn-save" id="saveBtn" onclick="saveChanges()">Save changes</button>

<div class="status-bar" id="statusBar"></div>

<script>
let addons = [];
let originalAddons = [];
let dragSrcIndex = null;
let touchStartY = 0;
let touchCurrentItem = null;
let placeholder = null;

async function loadAddons() {
  try {
    const res = await fetch('/api/addons');
    addons = await res.json();
    originalAddons = JSON.parse(JSON.stringify(addons));
    renderList();
  } catch (e) {
    showStatus('Failed to load addons', 'error');
  }
}

function renderList() {
  const list = document.getElementById('addonList');
  const empty = document.getElementById('emptyState');
  list.innerHTML = '';
  if (addons.length === 0) {
    empty.style.display = 'block';
    return;
  }
  empty.style.display = 'none';
  addons.forEach((addon, i) => {
    const li = document.createElement('li');
    li.className = 'addon-item';
    li.dataset.index = i;
    li.innerHTML =
      '<span class="drag-handle">&#x2630;</span>' +
      '<div class="addon-info">' +
        '<div class="addon-name">' + escapeHtml(addon.name || addon.url) +
          (addon.isNew ? '<span class="badge-new">NEW</span>' : '') +
        '</div>' +
        (addon.description ? '<div class="addon-desc">' + escapeHtml(addon.description) + '</div>' : '') +
        '<div class="addon-url">' + escapeHtml(addon.url) + '</div>' +
      '</div>' +
      '<div class="addon-actions">' +
        '<button class="btn btn-danger" onclick="removeAddon(' + i + ')">Remove</button>' +
      '</div>';

    // Touch drag-and-drop
    const handle = li.querySelector('.drag-handle');
    handle.addEventListener('touchstart', function(e) { onTouchStart(e, i, li); }, {passive: false});
    handle.addEventListener('touchmove', function(e) { onTouchMove(e, li); }, {passive: false});
    handle.addEventListener('touchend', function(e) { onTouchEnd(e, li); }, {passive: false});

    list.appendChild(li);
  });
}

function onTouchStart(e, index, item) {
  e.preventDefault();
  dragSrcIndex = index;
  touchStartY = e.touches[0].clientY;
  touchCurrentItem = item;
  item.classList.add('dragging');
}

function onTouchMove(e, item) {
  if (dragSrcIndex === null) return;
  e.preventDefault();
  const touch = e.touches[0];
  const dy = touch.clientY - touchStartY;
  item.style.transform = 'translateY(' + dy + 'px) scale(1.02)';

  // Find target based on touch position
  const items = document.querySelectorAll('.addon-item');
  items.forEach(el => el.classList.remove('drag-over'));
  const target = document.elementFromPoint(touch.clientX, touch.clientY);
  if (target) {
    const targetItem = target.closest('.addon-item');
    if (targetItem && targetItem !== item) {
      targetItem.classList.add('drag-over');
    }
  }
}

function onTouchEnd(e, item) {
  if (dragSrcIndex === null) return;
  item.classList.remove('dragging');
  item.style.transform = '';

  const items = document.querySelectorAll('.addon-item');
  items.forEach(el => el.classList.remove('drag-over'));

  // Determine drop target
  const touch = e.changedTouches[0];
  const target = document.elementFromPoint(touch.clientX, touch.clientY);
  if (target) {
    const targetItem = target.closest('.addon-item');
    if (targetItem && targetItem !== item) {
      const targetIndex = parseInt(targetItem.dataset.index);
      const movedAddon = addons.splice(dragSrcIndex, 1)[0];
      addons.splice(targetIndex, 0, movedAddon);
      renderList();
    }
  }

  dragSrcIndex = null;
  touchCurrentItem = null;
}

async function addAddon() {
  const input = document.getElementById('addonUrl');
  const addBtn = document.getElementById('addBtn');
  const errorEl = document.getElementById('addError');
  let url = input.value.trim();
  if (!url) return;

  // Normalize
  if (url.startsWith('stremio://')) {
    url = url.replace(/^stremio:\/\//, 'https://');
  }
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'https://' + url;
  }
  if (url.endsWith('/manifest.json')) {
    url = url.replace(/\/manifest\.json$/, '');
  }
  url = url.replace(/\/+$/, '');

  // Check for duplicates
  if (addons.some(a => a.url === url)) {
    errorEl.textContent = 'This addon is already in the list';
    errorEl.style.display = 'block';
    setTimeout(() => { errorEl.style.display = 'none'; }, 3000);
    return;
  }

  // Validate and fetch addon info from the TV
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
      addons.push({ url: data.url, name: data.name || url, description: data.description, isNew: true });
      input.value = '';
      renderList();
    }
  } catch (e) {
    errorEl.textContent = 'Failed to validate addon';
    errorEl.style.display = 'block';
    setTimeout(() => { errorEl.style.display = 'none'; }, 4000);
  }

  addBtn.disabled = false;
  addBtn.textContent = 'Add';
}

function removeAddon(index) {
  addons.splice(index, 1);
  renderList();
}

async function saveChanges() {
  const saveBtn = document.getElementById('saveBtn');
  saveBtn.disabled = true;

  const urls = addons.map(a => a.url);
  try {
    const res = await fetch('/api/addons', {
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
        // Reload the real addon list
        setTimeout(() => {
          loadAddons();
          hideStatus();
          document.getElementById('saveBtn').disabled = false;
        }, 2000);
      } else if (data.status === 'rejected') {
        showStatus('Changes were rejected on the TV', 'rejected');
        setTimeout(() => {
          addons = JSON.parse(JSON.stringify(originalAddons));
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

// Allow adding on Enter key
document.getElementById('addonUrl').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') addAddon();
});

loadAddons();
</script>
</body>
</html>
""".trimIndent()
}
