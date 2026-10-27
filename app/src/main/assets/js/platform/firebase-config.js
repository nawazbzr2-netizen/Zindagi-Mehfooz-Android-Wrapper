// ============================================================
// firebase-config.js — EPI Calculator Firebase project
// ------------------------------------------------------------
// Realtime Database: https://epi-calculator-default-rtdb.firebaseio.com
// Loaded after Firebase compat CDN scripts in index.html / admin-live.html
// ============================================================
(function () {
  var firebaseConfig = {
    apiKey: 'AIzaSyCHLKnOE-g6Ec-9vaLL2AWakK3flFRV5s8',
    authDomain: 'epi-calculator.firebaseapp.com',
    databaseURL: 'https://epi-calculator-default-rtdb.firebaseio.com',
    projectId: 'epi-calculator',
    storageBucket: 'epi-calculator.firebasestorage.app',
    messagingSenderId: '407353320886',
    appId: '1:407353320886:web:9281b71ff1d13fe071cd17'
  };

  window.EPI_FIREBASE_CONFIG = firebaseConfig;

  function init() {
    if (typeof firebase === 'undefined') {
      console.warn('Firebase SDK not loaded');
      return null;
    }
    try {
      if (!firebase.apps || !firebase.apps.length) {
        firebase.initializeApp(firebaseConfig);
      }
      return firebase.app();
    } catch (e) {
      console.warn('Firebase init', e);
      return null;
    }
  }

  window.EpiFirebase = {
    init: init,
    config: firebaseConfig,
    db: function () {
      init();
      try {
        return firebase.database();
      } catch (e) {
        return null;
      }
    }
  };
})();
