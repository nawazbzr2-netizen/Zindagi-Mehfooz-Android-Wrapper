let selectedFiles = [];

// Tells the Android app which kind of report the next file chooser is for, so it
// can pick that kind of PDF from the reports folder automatically.
function setPickerSource(src){
  try { if (window.AndroidEpi && typeof window.AndroidEpi.setPickerSource === 'function') window.AndroidEpi.setPickerSource(src); } catch (e) {}
}
function setPickerKind(kind){
  try { if (window.AndroidEpi && typeof window.AndroidEpi.setPickerKind === 'function') window.AndroidEpi.setPickerKind(kind); } catch (e) {}
}
// True when the app picked the files by itself (not the user): already-saved
// files are then skipped quietly instead of asking "Read again?" for each one.
function takeAutoPickFlag(){ const a = !!window.__epiAutoPick; window.__epiAutoPick = false; return a; }

btnPickDaily.addEventListener('click', async () => {
  // If any Daily Immunization Report is already saved, offer Insert or Delete.
  // Insert → phone file picker (Downloads). Delete → saved-files page.
  let savedCount = 0;
  try { savedCount = Object.keys(deriveFileList(loadStore())).length; } catch (e) {}
  if (savedCount > 0) {
    const choice = await chooseInsertOrDelete(
      savedCount + ' Daily Immunization Report file(s) already saved. Insert more, or delete existing?'
    );
    if (choice === 'delete') {
      if (typeof openSavedFilesPage === 'function') openSavedFilesPage('daily');
      return;
    }
    if (choice !== 'insert') return;
  }
  setPickerKind('daily');
  if (window.AndroidEpi && typeof window.AndroidEpi.setPickerSource === 'function') {
    const src = await chooseReportSource();
    if (!src) return;
    setPickerSource(src);
  }
  fileInput.click();
});
btnPickDaily.addEventListener('dragover', e => { e.preventDefault(); btnPickDaily.classList.add('drag'); });
btnPickDaily.addEventListener('dragleave', () => btnPickDaily.classList.remove('drag'));
btnPickDaily.addEventListener('drop', e => { e.preventDefault(); btnPickDaily.classList.remove('drag'); addFiles(e.dataTransfer.files); });
fileInput.addEventListener('change', e => addFiles(e.target.files));

async function addFiles(list){
  const auto = takeAutoPickFlag();
  const incoming = Array.from(list).filter(f => (f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf')) && !/defaulter/i.test(f.name));
  // Match on name + size, not name alone — different vaccinators often export
  // same-named "Daily Immunization Registry" PDFs for the same date, and those
  // are different files (different content/size) that must both be allowed in.
  const importedFiles = deriveFileList(loadStore());
  const skipped = [];
  const toAdd = [];
  const alreadySaved = [];
  for (const f of incoming) {
    const alreadyQueued = selectedFiles.some(sf => sf.name === f.name && sf.size === f.size);
    const alreadyImported = !!importedFiles[sourceKey(f.name, f.size)];
    if (alreadyQueued) { skipped.push(f.name); continue; }
    if (alreadyImported) { alreadySaved.push(f); continue; }
    toAdd.push(f);
  }
  // A file that is already saved can be read again. Nothing is duplicated: the
  // records stay as they are and only missing details (such as the EPI No,
  // which older saves did not read) are filled in.
  if (alreadySaved.length && auto) {
    skipped.push(...alreadySaved.map(f => f.name));
  } else if (alreadySaved.length) {
    const names = alreadySaved.map(f => f.name).join(', ');
    const again = await confirmDialog(`Already saved: ${names}. Read again to fill in missing EPI numbers? (Nothing will be duplicated.)`, 'Read again');
    if (again) toAdd.push(...alreadySaved);
    else skipped.push(...alreadySaved.map(f => f.name));
  }
  selectedFiles = selectedFiles.concat(toAdd);
  renderFileList();
  if (auto) {
    statusEl.innerHTML = `✔ ${toAdd.length} new file(s) picked automatically from the reports folder.` +
      (skipped.length ? ` ${skipped.length} already saved/added file(s) skipped.` : '');
  } else {
    statusEl.innerHTML = skipped.length
      ? `<span class="err">Already added, so not added again: ${skipped.map(escapeHtml).join(', ')}</span>`
      : '';
  }
}

const filelistExpandState = { filelist: true, defaultersFilelist: true };

function updateFilelistToggle(toggleId, labelId, listId, count, wasEmpty){
  const toggle = document.getElementById(toggleId);
  const list = document.getElementById(listId);
  const label = document.getElementById(labelId);
  if (count === 0) {
    toggle.style.display = 'none';
    list.style.display = 'none';
    return;
  }
  toggle.style.display = 'flex';
  label.textContent = `${count} file${count === 1 ? '' : 's'} added`;
  if (wasEmpty) filelistExpandState[listId] = true; // auto-expand as soon as files appear
  const expanded = filelistExpandState[listId];
  list.style.display = expanded ? 'block' : 'none';
  toggle.classList.toggle('open', expanded);
}

document.getElementById('filesToggle').addEventListener('click', () => {
  filelistExpandState.filelist = !filelistExpandState.filelist;
  document.getElementById('filelist').style.display = filelistExpandState.filelist ? 'block' : 'none';
  document.getElementById('filesToggle').classList.toggle('open', filelistExpandState.filelist);
});
document.getElementById('defaultersFilesToggle').addEventListener('click', () => {
  filelistExpandState.defaultersFilelist = !filelistExpandState.defaultersFilelist;
  document.getElementById('defaultersFilelist').style.display = filelistExpandState.defaultersFilelist ? 'block' : 'none';
  document.getElementById('defaultersFilesToggle').classList.toggle('open', filelistExpandState.defaultersFilelist);
});

function renderFileList(){
  const prevCount = filelistEl.dataset.count ? parseInt(filelistEl.dataset.count, 10) : 0;
  filelistEl.innerHTML = selectedFiles.map((f, i) => `
    <div class="frow"><span>📄 ${escapeHtml(f.name)}</span><button class="rmbtn" data-i="${i}" title="Remove" aria-label="Remove ${escapeHtml(f.name)}">✕</button></div>
  `).join('');
  filelistEl.querySelectorAll('.rmbtn').forEach(btn => {
    btn.addEventListener('click', () => {
      selectedFiles.splice(parseInt(btn.dataset.i,10), 1);
      renderFileList();
      updateProcessButtonState();
    });
  });
  updateFilelistToggle('filesToggle', 'filesToggleLabel', 'filelist', selectedFiles.length, prevCount === 0 && selectedFiles.length > 0);
  filelistEl.dataset.count = selectedFiles.length;
  updateProcessButtonState();
}

// processBtn does double duty: "process the queued files" when files are waiting, or
// "go forward to the results" when nothing is queued but data already exists.
function updateProcessButtonState(){
  if (!processBtn) return;
  const hasQueuedFiles = (typeof selectedFiles !== 'undefined' && selectedFiles.length > 0)
    || (typeof selectedDefaulterFiles !== 'undefined' && selectedDefaulterFiles.length > 0);
  let hasAnySavedData = false;
  try {
    const store = typeof loadStore === 'function' ? loadStore() : {};
    const defs = typeof loadDefaultersStore === 'function' ? loadDefaultersStore() : {};
    hasAnySavedData = !!(store && Object.keys(store).length) || !!(defs && Object.keys(defs).length);
  } catch (e) { hasAnySavedData = false; }
  if (hasQueuedFiles) {
    processBtn.textContent = 'Process and Save Data';
    processBtn.dataset.mode = 'process';
    processBtn.disabled = false;
  } else if (hasAnySavedData) {
    processBtn.textContent = 'Continue ⟶';
    processBtn.dataset.mode = 'forward';
    processBtn.disabled = false;
  } else {
    processBtn.textContent = 'Process and Save Data';
    processBtn.dataset.mode = 'process';
    processBtn.disabled = true;
  }
}

// ---- Defaulters List upload (separate icon + queue, but processed together with the button below) ----
const btnPickDefaulters = document.getElementById('btnPickDefaulters');
const defaultersFileInput = document.getElementById('defaultersFileInput');
const defaultersFilelistEl = document.getElementById('defaultersFilelist');

let selectedDefaulterFiles = [];

btnPickDefaulters.addEventListener('click', async () => {
  let savedCount = 0;
  try { savedCount = Object.keys(deriveDefaulterFileList(loadDefaultersStore())).length; } catch (e) {}
  if (savedCount > 0) {
    const choice = await chooseInsertOrDelete(
      savedCount + ' Children Defaulter Report file(s) already saved. Insert more, or delete existing?'
    );
    if (choice === 'delete') {
      if (typeof openSavedFilesPage === 'function') openSavedFilesPage('defaulters');
      return;
    }
    if (choice !== 'insert') return;
  }
  setPickerKind('defaulters');
  setPickerSource('seir');
  defaultersFileInput.click();
});
btnPickDefaulters.addEventListener('dragover', e => { e.preventDefault(); btnPickDefaulters.classList.add('drag'); });
btnPickDefaulters.addEventListener('dragleave', () => btnPickDefaulters.classList.remove('drag'));
btnPickDefaulters.addEventListener('drop', e => { e.preventDefault(); btnPickDefaulters.classList.remove('drag'); addDefaulterFiles(e.dataTransfer.files); });
defaultersFileInput.addEventListener('change', e => addDefaulterFiles(e.target.files));

function addDefaulterFiles(list){
  const auto = takeAutoPickFlag();
  const incoming = Array.from(list).filter(f => f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf'));
  // Unlike the Daily Registry (where re-importing would duplicate visits), the
  // Defaulters List is a SNAPSHOT that replaces entries by id — re-importing it
  // is a normal, safe way to refresh the data. An earlier version blocked this,
  // which made it impossible to re-read an already-imported file (for example
  // to pick up the EPI No). Same file already sitting in the queue is still
  // skipped; an already-imported file is allowed through with a note.
  const importedFiles = deriveDefaulterFileList(loadDefaultersStore());
  const reimported = [];
  const toAdd = [];
  for (const f of incoming) {
    if (selectedDefaulterFiles.some(sf => sf.name === f.name && sf.size === f.size)) continue;
    if (importedFiles[sourceKey(f.name, f.size)]) {
      if (auto) continue;          // auto-pick: leave already-imported files out
      reimported.push(f.name);
    }
    toAdd.push(f);
  }
  selectedDefaulterFiles = selectedDefaulterFiles.concat(toAdd);
  renderDefaulterFileList();
  updateProcessButtonState();
  if (auto) {
    statusEl.innerHTML = `✔ ${toAdd.length} new defaulters file(s) picked automatically from the reports folder.`;
  } else if (reimported.length) {
    statusEl.innerHTML = `Already imported before — it will be read again and the saved defaulters refreshed: ${reimported.map(escapeHtml).join(', ')}`;
  }
}

function renderDefaulterFileList(){
  const prevCount = defaultersFilelistEl.dataset.count ? parseInt(defaultersFilelistEl.dataset.count, 10) : 0;
  defaultersFilelistEl.innerHTML = selectedDefaulterFiles.map((f, i) => `
    <div class="frow"><span>📋 ${escapeHtml(f.name)}</span><button class="rmbtn" data-i="${i}" title="Remove" aria-label="Remove ${escapeHtml(f.name)}">✕</button></div>
  `).join('');
  defaultersFilelistEl.querySelectorAll('.rmbtn').forEach(btn => {
    btn.addEventListener('click', () => {
      selectedDefaulterFiles.splice(parseInt(btn.dataset.i,10), 1);
      renderDefaulterFileList();
      updateProcessButtonState();
    });
  });
  updateFilelistToggle('defaultersFilesToggle', 'defaultersFilesToggleLabel', 'defaultersFilelist', selectedDefaulterFiles.length, prevCount === 0 && selectedDefaulterFiles.length > 0);
  defaultersFilelistEl.dataset.count = selectedDefaulterFiles.length;
}

// Parses and saves whatever Defaulters List files are queued; returns how
// many were saved in total (across this and previous uploads) and a list of
// any files that failed to read, so the single Process button can report
// one combined status message covering both queues.
async function processDefaulterFilesQueue(onProgress){
  if (!selectedDefaulterFiles.length) return null;
  const defStore = loadDefaultersStoreCopy();
  const failedFiles = [];
  const skippedFiles = [];
  const filesToProcess = selectedDefaulterFiles.slice();

  // Phase 1: read every queued file, reject any page that doesn't actually
  // have the Defaulters List table structure, and note each file's report
  // period + center (for spotting an exact duplicate upload below).
  const fileInfos = [];
  for (const file of filesToProcess) {
    if (onProgress) onProgress(file.name);
    try {
      const buf = await file.arrayBuffer();
      const bytes = new Uint8Array(buf);
      const text = bytesToLatin1(bytes);
      const pages = await getAllPageTextRuns(bytes, text);

      const validPages = pages.filter(isValidDefaulterRuns);
      if (!validPages.length) {
        skippedFiles.push({ name: file.name, reason: 'does not look like a Children Defaulters Report (table headers not found)' });
        continue;
      }

      let recs = [];
      for (const runs of validPages) recs = recs.concat(extractDefaultersFromRuns(runs));
      if (!recs.length) {
        skippedFiles.push({ name: file.name, reason: 'no defaulter rows could be read from this file' });
        continue;
      }

      const normPage = normalizeDefaulterRunsOrientation(validPages[0]);
      const facility = (getFacilityName(normPage) || '').trim().toLowerCase();
      const dateRange = extractReportDateRange(normPage);
      const groupKey = dateRange ? `${dateRange}|${facility}` : null;
      fileInfos.push({ file, recs, groupKey });
    } catch (err) {
      console.error('failed on defaulters file', file.name, err);
      failedFiles.push(file.name);
    }
  }

  // Phase 2: the exact same report period for the exact same center,
  // uploaded more than once in this batch — keep only the copy listing the
  // most children. Files with a different period OR a different center are
  // never affected by this and are all kept.
  const groups = {};
  fileInfos.forEach(info => {
    const key = info.groupKey || `__unique__${info.file.name}|${info.file.size}`;
    (groups[key] = groups[key] || []).push(info);
  });
  const winners = [];
  Object.keys(groups).forEach(key => {
    const list = groups[key];
    if (list.length === 1) { winners.push(list[0]); return; }
    list.sort((a, b) => b.recs.length - a.recs.length);
    winners.push(list[0]);
    for (let i = 1; i < list.length; i++) {
      skippedFiles.push({ name: list[i].file.name, reason: `duplicate report for the same period/center — ${list[0].file.name} lists more children (${list[0].recs.length} vs ${list[i].recs.length}) and was kept instead` });
    }
  });

  // Phase 3: save the winners. Each defaulter is keyed by their QR code
  // (id) — the same child appearing in more than one file is merged into
  // one record instead of the later file silently overwriting the earlier
  // one (see mergeDefaulterRec).
  for (const info of winners) {
    const { file, recs } = info;
    for (const r of recs) {
      r.sources = [{ name: file.name, size: file.size }];
      const existing = defStore[r.id];
      defStore[r.id] = existing ? mergeDefaulterRec(existing, r) : r;
    }
  }

  const saveOk = saveDefaultersStore(defStore);
  selectedDefaulterFiles = [];
  renderDefaulterFileList();
  defaultersFileInput.value = '';
  renderProcessedFileLists();
  updateStorageNote();
  return { total: Object.keys(defStore).length, failedFiles, skippedFiles, saveOk };
}
