// Which saved-files list is currently on screen — set by whichever
// "Delete ... Report" button was pressed, so the same section/list can be
// reused to show either the Daily Immunization Report files only, or the
// Children Defaulter Report files only, instead of always mixing both.
let savedFilesMode = 'daily';

function renderProcessedFileLists(){
  const dailyRows = fileRowsHtml(deriveFileList(loadStore()), '📄', 'daily');
  const defRows = fileRowsHtml(deriveDefaulterFileList(loadDefaultersStore()), '📋', 'defaulters');
  const el = document.getElementById('processedList');
  if (savedFilesMode === 'defaulters') {
    el.innerHTML = defRows
      ? `<div class="section-header" style="margin-top:0;">Children Defaulter Report files</div>${defRows}`
      : '<div style="color:var(--muted);">No saved files.</div>';
  } else {
    el.innerHTML = dailyRows
      ? `<div class="section-header" style="margin-top:0;">Daily Immunization Report files</div>${dailyRows}`
      : '<div style="color:var(--muted);">No saved files.</div>';
  }
  el.querySelectorAll('.rmbtn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.dataset.kind === 'defaulters') deleteDefaulterFile(btn.dataset.key);
      else deleteFile(btn.dataset.key);
    });
  });
}

async function deleteDefaulterFile(key){
  const [name, size] = splitSourceKey(key);
  if (!await confirmDialog(`Delete "${name}" and its defaulters data?`, 'Delete')) return;
  const defStore = loadDefaultersStoreCopy();
  for (const id of Object.keys(defStore)) {
    const rec = defStore[id];
    if (!rec.sources) continue;
    rec.sources = rec.sources.filter(s => !sourceEntryMatches(s, name, size));
    if (rec.sources.length === 0) delete defStore[id];
  }
  saveDefaultersStore(defStore);
  renderProcessedFileLists();
  updateProcessButtonState();
  updateStorageNote();
}

function withTimeout(promise, ms){
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timed out')), ms);
    promise.then(v => { clearTimeout(t); resolve(v); }, e => { clearTimeout(t); reject(e); });
  });
}

// Whatever happens inside, the button comes back to life and the user can go on.
processBtn.addEventListener('click', async () => {
  try {
    await processQueuedFiles();
  } catch (err) {
    console.error('processing failed', err);
    try { selectedFiles = []; renderFileList(); fileInput.value = ''; } catch (e) {}
    statusEl.innerHTML = '<span class="err">Some files could not be processed and were ignored.</span> Press <b>Continue ⟶</b> to open the results.';
  } finally {
    processBtn.disabled = false;
    try { updateProcessButtonState(); } catch (e) {}
  }
});

async function processQueuedFiles(){
  if (processBtn.dataset.mode === 'forward') {
    document.getElementById('uploadView').style.display = 'none';
    resultsWrap.style.display = 'block';
    showHubView();
    window.scrollTo(0, 0);
    return;
  }
  if (!selectedFiles.length && !selectedDefaulterFiles.length) return;
  if (typeof DecompressionStream === 'undefined') {
    statusEl.innerHTML = '<span class="err">Your browser does not support DecompressionStream — please update your browser.</span>';
    return;
  }
  processBtn.disabled = true;

  const store = loadStoreCopy();   // private copy: a half-finished import can never leak into the shared one
  const failedFiles = [];
  const skippedFiles = [];
  const filesToProcess = selectedFiles.slice();
  let doneCount = 0;
  const totalToProcess = filesToProcess.length + selectedDefaulterFiles.length;
  // Every QR code (r.id) seen while reading this batch of Daily Immunization
  // Report file(s) — used afterwards to mark matching codes in the QR Code
  // inventory as "used" and remove them.
  const processedIdsThisRun = new Set();

  // Phase 1: read every queued file, reject any that doesn't genuinely have
  // the Daily Registry table structure (even if its name suggests it does —
  // point 4), and work out which vaccination date + vaccinator (or, when no
  // vaccinator name is printed, which center) it belongs to.
  const dailyFileInfos = [];
  for (const file of filesToProcess) {
    statusEl.innerHTML = `Reading file (${++doneCount}/${totalToProcess}): ${escapeHtml(file.name)} <span class="spinner"></span>`;
    try {
      const buf = await file.arrayBuffer();
      const bytes = new Uint8Array(buf);
      const text = bytesToLatin1(bytes);
      const pages = await withTimeout(getAllPageTextRuns(bytes, text), 60000);

      const validPages = pages.filter(isValidDailyRegistryRuns);
      if (!validPages.length) {
        skippedFiles.push({ name: file.name, reason: 'does not look like a Daily Immunization Report (table headers not found)' });
        continue;
      }

      let recs = [];
      let facility = null, vaccinatorName = null;
      for (const runs of validPages) {
        recs = recs.concat(extractRecordsFromRuns(runs));
        if (!facility) facility = getFacilityName(runs);
        if (!vaccinatorName) vaccinatorName = getVaccinatorName(runs);
      }
      if (!recs.length) {
        skippedFiles.push({ name: file.name, reason: 'no vaccination rows could be read from this file' });
        continue;
      }

      dailyFileInfos.push({ file, recs, meta: dailyFileMeta(recs, facility, vaccinatorName) });
    } catch (err) {
      // A file that can't be read is simply ignored; the other files carry on.
      console.error('failed on file', file.name, err);
      skippedFiles.push({ name: file.name, reason: 'could not be read — ignored' });
    }
  }

  // Phase 2 (points 1-3): files sharing the same vaccination date AND the
  // same vaccinator — or, when no vaccinator name is printed, the same
  // center — are duplicate copies of the same day's work; keep only the one
  // with the most children. Same date but a DIFFERENT vaccinator is not a
  // duplicate and every such file is kept (point 2).
  const dailyGroups = {};
  dailyFileInfos.forEach(info => {
    const key = (info.meta && info.meta.dateKey && info.meta.groupKey) ? `${info.meta.dateKey}||${info.meta.groupKey}` : `__unique__${info.file.name}|${info.file.size}`;
    (dailyGroups[key] = dailyGroups[key] || []).push(info);
  });
  const dailyWinners = [];
  Object.keys(dailyGroups).forEach(key => {
    const list = dailyGroups[key];
    if (list.length === 1) { dailyWinners.push(list[0]); return; }
    list.sort((a, b) => b.recs.length - a.recs.length);
    dailyWinners.push(list[0]);
    for (let i = 1; i < list.length; i++) {
      skippedFiles.push({ name: list[i].file.name, reason: `duplicate for the same date & vaccinator/center — ${list[0].file.name} has more children (${list[0].recs.length} vs ${list[i].recs.length}) and was kept instead` });
    }
  });

  // Phase 3: merge the kept files' records into the store.
  for (const info of dailyWinners) {
    const { file, recs } = info;
    for (const r of recs) {
      if (r.id) processedIdsThisRun.add(r.id);
      const existing = store[r.id];
      const cleanJunk = n => n ? n.replace(/\s*(total\s*rows?\s*:?\s*\d*|page\s*\d+\s*(of\s*\d+)?)\s*$/i, '').trim() || null : n;

      if (!existing) {
        r.sources = [{ name: file.name, size: file.size }];
        r.visits = [{ vaccDate: r.vaccDate, vaccinatorName: r.vaccinatorName, centerName: r.centerName, doses: { ...r.doses }, idStatusText: r.idStatusText || '', paracetamol: r.paracetamol || '' }];
        store[r.id] = r;
        continue;
      }

      const alreadyHasThisFile = existing.sources && existing.sources.some(s => sourceEntryMatches(s, file.name, file.size));
      if (!existing.sources) existing.sources = [{ name: file.name, size: file.size }];
      else if (!alreadyHasThisFile) existing.sources.push({ name: file.name, size: file.size });

      // Backfill gender onto a record saved before this field existed (or
      // whose earlier import couldn't read the Gender column) once a newer
      // import provides it.
      if (!existing.gender && r.gender) existing.gender = r.gender;
      // Same for the EPI No (saved records from before it was read have none).
      if (!existing.epiNo && r.epiNo) existing.epiNo = r.epiNo;

      // Migrate a record saved before per-visit tracking existed: seed
      // its single visit from what's already on it (cleaning up any old
      // "Total Rows: N" junk baked into a previously-saved vaccinator name).
      if (!existing.visits) {
        existing.visits = [{ vaccDate: existing.vaccDate, vaccinatorName: cleanJunk(existing.vaccinatorName), centerName: existing.centerName, doses: { ...existing.doses } }];
      }

      // A genuinely new visit for this same child/woman — merge its doses
      // into the running total (so counting/classification sees every dose
      // ever recorded) and add it as its own dated visit so the detail view
      // can show exactly who gave what and when.
      // The test is the VISIT's own content, not which file it came from:
      // two rows for the same person inside one PDF are two real visits and
      // both must be kept, while re-importing the identical row is ignored.
      const newVisit = { vaccDate: r.vaccDate, vaccinatorName: r.vaccinatorName, centerName: r.centerName, doses: { ...r.doses }, idStatusText: r.idStatusText || '', paracetamol: r.paracetamol || '' };
      const newKey = visitKey(newVisit);
      if (!existing.visits.some(v => visitKey(v) === newKey)) {
        existing.visits.push(newVisit);
        existing.doses = mergeDoses(existing.doses, r.doses);
        if (compareDateObjs(r.vaccDate, existing.vaccDate) > 0) existing.vaccDate = r.vaccDate;
      }

      // Keep the top-level Vaccinator/Center in sync with the most
      // recent visit (used whenever there's only a single visit to show).
      const latestVisit = existing.visits.slice().sort((a, b) => compareDateObjs(a.vaccDate, b.vaccDate)).pop();
      if (latestVisit) {
        if (latestVisit.vaccinatorName) existing.vaccinatorName = latestVisit.vaccinatorName;
        if (latestVisit.centerName) existing.centerName = latestVisit.centerName;
      }
    }
  }

  const saveOk = saveStore(store);
  // Only SAVE here. The In UC / Out of UC / dashboard results are built the first time a result screen is opened.
  refreshResultsLazy();

  selectedFiles = [];
  renderFileList();
  fileInput.value = '';

  // Process any queued Defaulters List files together with the same button,
  // reusing the same progress line and combining into one final message.
  const defResult = await processDefaulterFilesQueue(name => {
    statusEl.innerHTML = `Reading file (${++doneCount}/${totalToProcess}): ${escapeHtml(name)} <span class="spinner"></span>`;
  });
  if (defResult && defResult.failedFiles.length) failedFiles.push(...defResult.failedFiles);
  if (defResult && defResult.skippedFiles.length) skippedFiles.push(...defResult.skippedFiles);

  updateProcessButtonState();

  // If either store failed to save (browser storage full), the warning that
  // saveStore/saveDefaultersStore already put on screen MUST stand. It used to
  // be overwritten a few lines later by "Complete — data has been saved", so
  // the user was told everything was saved when nothing was.
  const defSaveOk = !defResult || defResult.saveOk !== false;
  if (!saveOk || !defSaveOk) {
    statusEl.innerHTML = '<span class="err">⚠ Data could NOT be saved — browser storage is full. Delete some old saved files, then try again.</span>';
    return;
  }

  let msg = failedFiles.length
    ? `<span class="err">These files could not be read: ${failedFiles.map(escapeHtml).join(', ')}</span>`
    : 'Complete — data from all files has been saved.';

  if (skippedFiles.length) {
    const items = skippedFiles.map(s => `<li>${escapeHtml(s.name)} — ${escapeHtml(s.reason)}</li>`).join('');
    msg += `<div class="note" style="margin-top:8px;">${skippedFiles.length} file(s) were not inserted:<ul style="margin:4px 0 0 18px;padding:0;">${items}</ul></div>`;
  }

  // Tell the person whether the EPI No could be read from the Identifier column
  // (otherwise a parsing miss is only noticed by opening a card and seeing a dash).
  const dailyReadCount = dailyWinners.reduce((n, info) => n + info.recs.length, 0);
  if (dailyReadCount) {
    const dailyWithEpi = dailyWinners.reduce((n, info) => n + info.recs.filter(r => r.epiNo).length, 0);
    msg += ` Daily Immunization: ${dailyReadCount} record(s) read, ${dailyWithEpi} with an EPI No.`;
    if (!dailyWithEpi) msg += ' <span class="err">No EPI No could be read from the Identifier column — please share this PDF so the layout can be checked.</span>';
  }

  // ---- QR Code inventory reconciliation ----
  // Any unused QR code seen in the Daily Immunization Report(s) just
  // processed is now marked "used" (it stays saved, in the Used section of
  // the Saved QR Codes page, rather than being deleted).
  if (processedIdsThisRun.size) {
    const qrEntries = loadQrStore();
    if (qrEntries.length) {
      const newlyUsed = [];
      qrEntries.forEach(e => {
        if (!e.used && processedIdsThisRun.has(e.code)) { e.used = true; newlyUsed.push(e.code); }
      });
      if (newlyUsed.length) {
        saveQrStore(qrEntries);
        newlyUsed.sort();
        const stillUnused = qrEntries.filter(e => !e.used).length;
        msg += ` QR Code Inventory: ${newlyUsed.length} code(s) marked as used (${newlyUsed.map(escapeHtml).join(', ')}) — ${stillUnused} QR code(s) still unused.`;
      }
    }
    updateQrTotalCount();
  }

  if (defResult) {
    // Counting "Covered" here would check every defaulter against every saved record right now.
    // That work is done when Defaulter Children / Covered is opened instead.
    const withEpi = Object.values(loadDefaultersStore()).filter(d => d.epiNo).length;
    msg += ` Defaulters List: ${defResult.total} record(s) saved, ${withEpi} have an EPI No.`;
    if (defResult.total && !withEpi) {
      msg += ` <span class="err">No EPI No could be read from the Identifier column — please share this PDF so the layout can be checked.</span>`;
    }
  }
  msg += ' Press <b>Continue ⟶</b> to open the results.';
  statusEl.innerHTML = msg;
}

async function deleteFile(key){
  const [name, size] = splitSourceKey(key);
  if (!await confirmDialog(`Delete "${name}" and its data?`, 'Delete')) return;
  const store = loadStoreCopy();
  for (const id of Object.keys(store)) {
    const rec = store[id];
    if (!rec.sources) continue;
    rec.sources = rec.sources.filter(s => !sourceEntryMatches(s, name, size));
    if (rec.sources.length === 0) delete store[id];
  }
  saveStore(store);
  renderResults(store, true);
}

clearBtn.addEventListener('click', async () => {
  if (!await confirmDialog('Clear ALL saved data? This removes every saved record AND the defaulters list from this device.', 'Clear everything')) return;
  saveStore({});
  // The button says "All", so the Defaulters List must go too — it used to be
  // left behind, which made the app look half-cleared afterwards.
  saveDefaultersStore({});
  renderResults({});
  renderProcessedFileLists();
  updateProcessButtonState();
  updateStorageNote();
  statusEl.textContent = 'All saved data has been cleared.';
});

function openSavedFilesPage(mode){
  savedFilesMode = mode === 'defaulters' ? 'defaulters' : 'daily';
  // Hide QR panels if open
  const qrIns = document.getElementById('qrCodeSection');
  const qrDel = document.getElementById('qrDeleteView');
  if (qrIns) qrIns.style.display = 'none';
  if (qrDel) qrDel.style.display = 'none';
  const sec = document.getElementById('savedFilesSection');
  renderProcessedFileLists();
  sec.style.display = 'block';
  if (sec.scrollIntoView) sec.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
window.openSavedFilesPage = openSavedFilesPage;

function closeSavedFilesPage(){
  const sec = document.getElementById('savedFilesSection');
  if (sec) sec.style.display = 'none';
  window.scrollTo(0, 0);
}

// Delete All on the saved-files page — only the list currently shown (daily OR defaulters)
const btnDeleteAllSaved = document.getElementById('btnDeleteAllSavedFiles');
if (btnDeleteAllSaved) {
  btnDeleteAllSaved.addEventListener('click', async () => {
    if (savedFilesMode === 'defaulters') {
      const keys = Object.keys(deriveDefaulterFileList(loadDefaultersStore()));
      if (!keys.length) {
        if (statusEl) statusEl.textContent = 'No defaulter files to delete.';
        closeSavedFilesPage();
        return;
      }
      if (!await confirmDialog('Delete ALL Children Defaulter Report files and their data?', 'Delete All')) return;
      saveDefaultersStore({});
    } else {
      const keys = Object.keys(deriveFileList(loadStore()));
      if (!keys.length) {
        if (statusEl) statusEl.textContent = 'No daily files to delete.';
        closeSavedFilesPage();
        return;
      }
      if (!await confirmDialog('Delete ALL Daily Immunization Report files and their data?', 'Delete All')) return;
      saveStore({});
    }
    renderResults(loadStore(), true);
    renderProcessedFileLists();
    updateProcessButtonState();
    updateStorageNote();
    if (statusEl) statusEl.textContent = 'All files in this list have been deleted.';
    closeSavedFilesPage();
  });
}

// ---- QR Code inventory pages ----
document.getElementById('btnGoQrCode').addEventListener('click', async () => {
  let qrCount = 0;
  try { qrCount = (loadQrStore() || []).length; } catch (e) {}
  if (qrCount > 0) {
    const choice = await chooseInsertOrDelete(
      qrCount + ' QR code(s) already saved. Insert more, or delete existing?'
    );
    if (choice === 'delete') {
      openQrDeletePage();
      return;
    }
    if (choice !== 'insert') return;
  }
  openQrInsertPage();
});

function openQrInsertPage(){
  const del = document.getElementById('qrDeleteView');
  if (del) del.style.display = 'none';
  const files = document.getElementById('savedFilesSection');
  if (files) files.style.display = 'none';
  const sec = document.getElementById('qrCodeSection');
  updateQrTotalCount();
  if (typeof renderQrInsertList === 'function') renderQrInsertList();
  sec.style.display = 'block';
  if (sec.scrollIntoView) sec.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function openQrDeletePage(){
  const ins = document.getElementById('qrCodeSection');
  if (ins) ins.style.display = 'none';
  const files = document.getElementById('savedFilesSection');
  if (files) files.style.display = 'none';
  updateQrTotalCount();
  const sec = document.getElementById('qrDeleteView');
  sec.style.display = 'block';
  if (sec.scrollIntoView) sec.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

document.getElementById('qrAddRangeBtn').addEventListener('click', () => {
  const qrStatus = document.getElementById('qrStatus');
  const startInput = document.getElementById('qrRangeStart');
  const endInput = document.getElementById('qrRangeEnd');
  const startRaw = startInput.value.trim();
  const endRaw = endInput.value.trim();
  const dateVal = document.getElementById('qrDate').value || null;

  if (!/^\d+$/.test(startRaw) || !/^\d+$/.test(endRaw) || startRaw.length > QR_CODE_MAX_LEN || endRaw.length > QR_CODE_MAX_LEN) {
    qrStatus.innerHTML = `<span class="err">A QR Code is a ${QR_CODE_MAX_LEN}-digit number — please enter valid numeric QR codes (up to ${QR_CODE_MAX_LEN} digits) in both fields.</span>`;
    return;
  }

  // Preserve leading zeros / digit-count (e.g. 14-digit QR codes) by padding
  // every generated code to the longer of the two entered lengths — this must
  // match the exact string form the 14-digit QR code takes when read from a
  // Daily Immunization Report PDF, or reconciliation later will silently miss it.
  const len = Math.max(startRaw.length, endRaw.length);
  let startNum = parseInt(startRaw, 10);
  let endNum = parseInt(endRaw, 10);
  if (startNum > endNum) { const t = startNum; startNum = endNum; endNum = t; }
  const count = endNum - startNum + 1;

  if (count > 20000) {
    qrStatus.innerHTML = '<span class="err">That range has ' + count.toLocaleString() + ' codes — please add it in smaller batches (max 20,000 at a time).</span>';
    return;
  }

  const existing = loadQrStore();
  const seen = new Set(existing.map(e => e.code));
  let added = 0;
  for (let n = startNum; n <= endNum; n++) {
    const code = String(n).padStart(len, '0');
    if (!seen.has(code)) { seen.add(code); existing.push({ code, date: dateVal, used: false }); added++; }
  }
  if (!saveQrStore(existing)) return;

  startInput.value = '';
  endInput.value = '';
  qrStatus.innerHTML = `<span class="ok">${added} QR code(s) added${count - added ? ` (${count - added} were already saved)` : ''}.</span> See the list below or the <b>QR Codes</b> option on the Results Menu.`;
  updateQrTotalCount();
  if (typeof renderQrInsertList === 'function') renderQrInsertList();
});

// ---- QR Code delete page ----
// btnGoQrDelete removed — open via Insert QR → Delete popup (openQrDeletePage)


document.getElementById('qrDeleteListBtn').addEventListener('click', () => {
  const status = document.getElementById('qrDeleteStatus');
  const input = document.getElementById('qrDeleteListInput');
  const toDelete = new Set(input.value.split(/[\n,\s]+/).map(s => s.trim()).filter(Boolean));

  if (!toDelete.size) {
    status.innerHTML = '<span class="err">Type or paste at least one QR Code to delete first.</span>';
    return;
  }

  const existing = loadQrStore();
  const remaining = existing.filter(e => !toDelete.has(e.code));
  const removedCount = existing.length - remaining.length;
  if (!saveQrStore(remaining)) return;

  input.value = '';
  status.innerHTML = removedCount
    ? `<span class="ok">${removedCount} QR code(s) deleted.</span>`
    : '<span class="err">None of those codes were found in the saved list.</span>';
  updateQrTotalCount();
});

document.getElementById('qrClearAllBtn').addEventListener('click', async () => {
  if (!await confirmDialog('Clear ALL saved QR codes from this device?', 'Clear QR Codes')) return;
  saveQrStore([]);
  updateQrTotalCount();
  document.getElementById('qrDeleteStatus').textContent = 'All saved QR codes have been cleared.';
  const sec = document.getElementById('qrDeleteView');
  if (sec) sec.style.display = 'none';
  window.scrollTo(0, 0);
});
