// ============================================================
// DIALOGS + SMALL TEXT HELPERS — confirmDialog, chooseAction (Share/Download),
// chooseExportStyle (Card/Print), escapeHtml and label helpers.
// ============================================================

// ---------- Modal stack ----------
// Every on-screen dialog below (confirmDialog / chooseExportStyle / chooseAction)
// registers its own "cancel" here while it is open. This lets the phone's
// hardware/gesture BACK button close just the dialog on top instead of falling
// through to the page navigation underneath — which, before this existed, could
// land on the main menu with nothing "open" (as far as navigation.js could see)
// and pop the "Do you want to exit the app?" confirmation on top of whatever
// dialog the person was actually trying to dismiss. See js/platform/navigation.js.
const openModalStack = [];

// Called by navigation.js before it does anything else with a back press.
// Returns true (and closes the top-most dialog as a cancel) if one was open.
function closeTopModal(){
  if (!openModalStack.length) return false;
  openModalStack[openModalStack.length - 1]();
  return true;
}

// Replacement for window.confirm(). Inside a sandboxed iframe (and in some
// Android WebView builds) confirm() never opens and returns undefined, so
// `if (!confirm(...)) return;` bailed out every time and buttons like
// "Clear All Saved Data" and the 🗑 delete buttons appeared completely dead.
// This draws the question in the page itself, so it works everywhere.
function confirmDialog(message, okLabel, cancelLabel, yesFirst){
  return new Promise(resolve => {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true">
      <div class="modal-msg"></div>
      <div class="modal-actions">
        <button type="button" class="go secondary" data-answer="no">Cancel</button>
        <button type="button" class="go danger" data-answer="yes"></button>
      </div>
    </div>`;
    back.querySelector('.modal-msg').textContent = message;
    if (yesFirst) { const acts = back.querySelector('.modal-actions'); acts.insertBefore(back.querySelector('[data-answer="yes"]'), acts.firstChild); }
    back.querySelector('[data-answer="yes"]').textContent = okLabel || 'Yes, continue';
    if (cancelLabel) back.querySelector('[data-answer="no"]').textContent = cancelLabel;
    let settled = false;
    const cancelFromBack = () => finish(false);
    const finish = value => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(false); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish(false);           // tap outside = cancel
      const btn = e.target.closest('[data-answer]');
      if (btn) finish(btn.dataset.answer === 'yes');
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
    const yes = back.querySelector('[data-answer="yes"]');
    if (yes && yes.focus) yes.focus();
  });
}

// Lets the person pick between a full data table ("Print Style") and a
// one-card-per-record layout ("Card Style", matching the on-screen record
// cards) before a PDF is built. Resolves to 'print', 'card', or null if the
// person cancelled.
// Simple one-button info popup (e.g. "There is Nothing").
function alertDialog(message, okLabel){
  return new Promise(resolve => {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true">
      <div class="modal-msg"></div>
      <div class="modal-actions">
        <button type="button" class="go" data-answer="ok"></button>
      </div>
    </div>`;
    back.querySelector('.modal-msg').textContent = message || '';
    back.querySelector('[data-answer="ok"]').textContent = okLabel || 'OK';
    let settled = false;
    const cancelFromBack = () => finish();
    const finish = () => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve();
    };
    const onKey = e => { if (e.key === 'Escape' || e.key === 'Enter') finish(); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish();
      if (e.target.closest('[data-answer]')) finish();
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
    const ok = back.querySelector('[data-answer="ok"]');
    if (ok && ok.focus) ok.focus();
  });
}

// Insert vs Delete choice for the three main Insert tiles when data already exists.
// Resolves to 'insert' | 'delete' | null (cancelled).
function chooseInsertOrDelete(message){
  return new Promise(resolve => {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true">
      <div class="modal-msg"></div>
      <div class="modal-actions" style="flex-direction:column;gap:8px;">
        <button type="button" class="go" data-answer="insert">➕ Insert</button>
        <button type="button" class="go danger" data-answer="delete">🗑 Delete</button>
        <button type="button" class="go secondary" data-answer="cancel" style="background:transparent;border:none;color:var(--muted);margin-top:0;">Cancel</button>
      </div>
    </div>`;
    back.querySelector('.modal-msg').textContent = message || 'Insert new data, or manage / delete what is already saved?';
    let settled = false;
    const cancelFromBack = () => finish(null);
    const finish = value => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(null); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish(null);
      const btn = e.target.closest('[data-answer]');
      if (!btn) return;
      if (btn.dataset.answer === 'cancel') finish(null);
      else finish(btn.dataset.answer);
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
  });
}

// Where to take the Daily Immunization Report PDFs from (4 buttons).
// Resolves to 'seir' | 'download' | 'whatsapp' | 'storage', or null if cancelled.
function chooseReportSource(){
  return new Promise(resolve => {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true">
      <div class="modal-msg">Take the Daily Immunization Report files from:</div>
      <div class="modal-actions" style="flex-direction:column;gap:8px;">
        <button type="button" class="go" data-answer="seir">📁 SEIR Folder</button>
        <button type="button" class="go" data-answer="download">⬇ From Download</button>
        <button type="button" class="go" data-answer="whatsapp">💬 From WhatsApp</button>
        <button type="button" class="go" data-answer="storage">💾 Storage</button>
        <button type="button" class="go secondary" data-answer="cancel" style="background:transparent;border:none;color:var(--muted);margin-top:0;">Cancel</button>
      </div>
    </div>`;
    let settled = false;
    const cancelFromBack = () => finish(null);
    const finish = value => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(null); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish(null);
      const btn = e.target.closest('[data-answer]');
      if (!btn) return;
      if (btn.dataset.answer === 'cancel') finish(null);
      else finish(btn.dataset.answer);
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
  });
}

function chooseExportStyle(){
  return new Promise(resolve => {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true">
      <div class="modal-msg">Choose a style for this PDF:</div>
      <div class="modal-actions" style="flex-direction:column;gap:8px;">
        <button type="button" class="go secondary" data-answer="card">🗂 Card Style (one card per child, like on screen)</button>
        <button type="button" class="go" data-answer="print">📋 Print Style (compact table)</button>
        <button type="button" class="go secondary" data-answer="cancel" style="background:transparent;border:none;color:var(--muted);margin-top:0;">Cancel</button>
      </div>
    </div>`;
    let settled = false;
    const cancelFromBack = () => finish(null);
    const finish = value => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(null); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish(null);            // tap outside = cancel
      const btn = e.target.closest('[data-answer]');
      if (btn) finish(btn.dataset.answer === 'cancel' ? null : btn.dataset.answer);
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
  });
}

// First step of the export flow: Share (native share sheet) or Download
// (save to the device). Resolves to 'share', 'download', or null if
// the person cancelled.
function chooseAction(){
  return new Promise(resolve => {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true">
      <div class="modal-msg">Share or download this result?</div>
      <div class="modal-actions" style="flex-direction:column;gap:8px;">
        <button type="button" class="go" data-answer="share">📤 Share</button>
        <button type="button" class="go secondary" data-answer="download">⬇ Download</button>
        <button type="button" class="go secondary" data-answer="cancel" style="background:transparent;border:none;color:var(--muted);margin-top:0;">Cancel</button>
      </div>
    </div>`;
    let settled = false;
    const cancelFromBack = () => finish(null);
    const finish = value => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(null); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish(null);            // tap outside = cancel
      const btn = e.target.closest('[data-answer]');
      if (btn) {
        // A tap is the only moment a phone lets the app ask for notification
        // permission, so it is asked here (used for the download notification).
        if (btn.dataset.answer === 'download') NativeBridge.primeNotifications();
        finish(btn.dataset.answer === 'cancel' ? null : btn.dataset.answer);
      }
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
  });
}

// Escapes for BOTH text content and attribute values. The old version used
// textContent -> innerHTML, which leaves " and ' untouched — and this output is
// placed inside attributes such as data-key="..." and title="...", so a
// filename or address containing a quote could break the markup.
function escapeHtml(str){
  return String(str == null ? '' : str)
    .replace(/&/g, '&')
    .replace(/</g, '<')
    .replace(/>/g, '>')
    .replace(/"/g, '"')
    .replace(/'/g, '&#39;');
}

// Combine the vaccinator's name with the health center/facility name printed
// on the source PDF (e.g. "Sakhawat — GD Khalidabad"), so the Vaccinator
// field always shows both pieces of information together when known.
function vaccinatorCenterLabel(r){
  const v = (r.vaccinatorName || '').trim();
  const c = (r.centerName || '').trim();
  if (v && c) return `${v} — ${c}`;
  if (v) return v;
  if (c) return c;
  return null;
}

// Escape each line separately and join with <br> so a multi-visit breakdown
// (one line per visit) renders as separate lines instead of running together.
function escapeHtmlMultiline(text){
  return String(text == null ? '' : text).split('\n').map(escapeHtml).join('<br>');
}

// The vaccine(s) actually given during one specific visit (own doses snapshot).
function visitTagLabel(v){
  const isTt = isTtRecord(v.doses);
  const tags = isTt ? classifyTT(v.doses) : classifyEpi(v.doses);
  if (!tags.length) return isTt ? 'No TT dose recorded' : 'No vaccine recorded';
  return tags.map(t => isTt ? (TT_LABELS[t] || t) : (EPI_SHORT[t] || t)).join(', ');
}

// When a child/woman has more than one recorded visit (different date and/or
// different vaccinator/center), build a per-visit breakdown so "Vaccination
// Date" and "Vaccinator" each show every visit's date and who gave it,
// lined up against exactly which vaccine that visit was for. With only one
// visit (or on older records saved before visit-tracking existed), this
// falls back to the plain single-value text as before.
function dateAndVaccinatorText(r){
  const singleDate = dmy(r.vaccDate) || '—';
  const singleWho = vaccinatorCenterLabel(r) || '—';
  if (!r.visits || r.visits.length <= 1) return { dateText: singleDate, vaccinatorText: singleWho };
  const sorted = r.visits.slice().sort((a, b) => compareDateObjs(a.vaccDate, b.vaccDate));
  const dateText = sorted.map(v => `${visitTagLabel(v)}: ${dmy(v.vaccDate) || '—'}`).join('\n');
  const vaccinatorText = sorted.map(v => `${visitTagLabel(v)}: ${vaccinatorCenterLabel(v) || '—'}`).join('\n');
  return { dateText, vaccinatorText };
}


// One line per visit: "Date → Vaccine → Vaccinator Name" (vaccinator's name
// only, no centre). More than one visit = one such line each, oldest first,
// same format every time. Used by every Results output (screen cards, PDF
// table, PDF cards, print fallback).
function visitLinesText(r){
  // Prefer vaccinator name; if missing on the Daily Report, use centre/facility name
  const who = v => {
    // 1) Vaccinator who gave the dose  2) else centre name  3) else facility
    const vn = ((v && v.vaccinatorName) || '').trim();
    if (vn) return vn;
    const cn = ((v && v.centerName) || '').trim();
    if (cn) return cn;
    const rv = (r.vaccinatorName || '').trim();
    if (rv) return rv;
    const rc = (r.centerName || '').trim();
    if (rc) return rc;
    const fac = (r.facility || '').trim();
    if (fac && fac !== 'Out From Taluka' && fac !== 'Out From District' && fac !== 'Unknown Name') return fac;
    return '—';
  };
  const visits = (r.visits && r.visits.length > 1)
    ? r.visits.slice().sort((a, b) => compareDateObjs(a.vaccDate, b.vaccDate))
    : null;
  if (!visits) {
    const isTt = isTtRecord(r.doses);
    const tags = isTt ? classifyTT(r.doses) : classifyEpi(r.doses);
    const vax = tags.length
      ? tags.map(t => isTt ? (TT_LABELS[t] || t) : (EPI_SHORT[t] || t)).join(', ')
      : (isTt ? 'No TT dose recorded' : 'No vaccine recorded');
    return `${dmy(r.vaccDate) || '—'} \u2192 ${vax} \u2192 ${who(r)}`;
  }
  return visits.map(v => `${dmy(v.vaccDate) || '—'} \u2192 ${visitTagLabel(v)} \u2192 ${who(v)}`).join('\n');
}

// Generic "pick several from a list" dialog used by the Daily Immunization
// Report Month / Vaccinator+Center filters. items = [{key,label,count}].
// selected: Set of keys already checked, or 'all' (= everything checked).
// Resolves to 'all' (every item ended up checked), a Set of chosen keys, or
// null if cancelled. allLabel is the text for the "select everything" row.
function checkboxPickerDialog(title, items, selected, allLabel){
  return new Promise(resolve => {
    const selSet = selected === 'all' ? new Set(items.map(i => i.key)) : new Set(selected);
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    const rows = items.map(it => `
      <label style="display:flex;align-items:center;gap:10px;padding:10px 4px;border-bottom:1px solid #eee;">
        <input type="checkbox" data-k="${escapeHtml(it.key)}" ${selSet.has(it.key) ? 'checked' : ''} style="width:19px;height:19px;flex:none;">
        <span style="flex:1;">${escapeHtml(it.label)}</span>
        <span style="color:var(--muted);font-size:12.5px;">${it.count}</span>
      </label>`).join('');
    back.innerHTML = `<div class="modal-box" role="dialog" aria-modal="true" style="max-height:82vh;display:flex;flex-direction:column;">
      <div class="modal-msg" style="margin-bottom:2px;">${escapeHtml(title)}</div>
      <label style="display:flex;align-items:center;gap:10px;padding:8px 4px;border-bottom:2px solid #ddd;font-weight:700;">
        <input type="checkbox" id="cbPickAll" ${selSet.size === items.length ? 'checked' : ''} style="width:19px;height:19px;flex:none;">
        <span>${escapeHtml(allLabel || 'All')}</span>
      </label>
      <div style="overflow-y:auto;flex:1;min-height:0;margin:2px 0 10px;">${rows || '<div style="color:var(--muted);padding:10px 4px;">Nothing to choose from yet.</div>'}</div>
      <div class="modal-actions">
        <button type="button" class="go secondary" data-answer="cancel">Cancel</button>
        <button type="button" class="go" data-answer="ok">OK</button>
      </div>
    </div>`;
    const boxes = () => Array.from(back.querySelectorAll('input[data-k]'));
    const allBox = back.querySelector('#cbPickAll');
    allBox.addEventListener('change', () => { boxes().forEach(b => { b.checked = allBox.checked; }); });
    back.querySelectorAll('input[data-k]').forEach(b => b.addEventListener('change', () => {
      allBox.checked = boxes().every(x => x.checked);
    }));
    let settled = false;
    const cancelFromBack = () => finish(null);
    const finish = value => {
      if (settled) return;
      settled = true;
      back.remove();
      document.removeEventListener('keydown', onKey);
      const idx = openModalStack.indexOf(cancelFromBack);
      if (idx !== -1) openModalStack.splice(idx, 1);
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(null); };
    back.addEventListener('click', e => {
      if (e.target === back) return finish(null);
      const btn = e.target.closest('[data-answer]');
      if (!btn) return;
      if (btn.dataset.answer === 'cancel') return finish(null);
      const checked = boxes().filter(b => b.checked).map(b => b.dataset.k);
      if (!checked.length) return finish(new Set()); // caller decides what an empty pick means
      finish(checked.length === items.length ? 'all' : new Set(checked));
    });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(back);
    openModalStack.push(cancelFromBack);
  });
}
