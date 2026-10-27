// ============================================================
// UI + persistent storage
// ============================================================

const btnPickDaily = document.getElementById('btnPickDaily');
const fileInput = document.getElementById('fileInput');
const filelistEl = document.getElementById('filelist');
const processBtn = document.getElementById('processBtn');
const clearBtn = document.getElementById('clearBtn');
const statusEl = document.getElementById('status');
const resultsWrap = document.getElementById('resultsWrap');

// Shared "go back to the main upload menu (Page 1)" action — used by the
// back-button handling in js/platform/navigation.js (VIEW_BACK_ACTIONS.hubView).
function goToMainMenu(){
  resultsWrap.style.display = 'none';
  document.getElementById('uploadView').style.display = 'block';
  updateProcessButtonState();
  window.scrollTo(0, 0);
}
