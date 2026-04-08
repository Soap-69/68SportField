(function () {
  'use strict';

  let toastEl = null;
  let timer   = null;

  function getOrCreate() {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.id = 'csToast';
      toastEl.className = 'cs-toast';
      toastEl.setAttribute('role', 'status');
      toastEl.setAttribute('aria-live', 'polite');
      document.body.appendChild(toastEl);
    }
    return toastEl;
  }

  /**
   * Display a toast notification.
   * @param {string} message - Text to display
   * @param {'success'|'error'} type - Visual variant (default: 'success')
   */
  window.showToast = function (message, type) {
    var el = getOrCreate();
    clearTimeout(timer);
    el.textContent = message;
    el.className   = 'cs-toast cs-toast-' + (type || 'success');
    // Force reflow so transition re-triggers if toast is already visible
    void el.offsetWidth;
    el.classList.add('cs-toast-show');
    timer = setTimeout(function () {
      el.classList.remove('cs-toast-show');
    }, 3000);
  };
})();
