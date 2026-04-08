(function () {
  'use strict';

  var form = document.getElementById('inquiryForm');
  if (!form) return;

  var submitBtn = form.querySelector('[type="submit"]');

  function field(name) {
    return form.querySelector('[name="' + name + '"]');
  }

  function validateForm() {
    var valid = true;

    // Required fields
    form.querySelectorAll('[required]').forEach(function (el) {
      var empty = !el.value.trim();
      el.classList.toggle('cs-inquiry-input-error', empty);
      if (empty) valid = false;
    });

    // Email format (only if non-empty — emptiness is caught by required check above)
    var emailEl = field('customerEmail');
    if (emailEl && emailEl.value.trim()) {
      var ok = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailEl.value.trim());
      emailEl.classList.toggle('cs-inquiry-input-error', !ok);
      if (!ok) valid = false;
    }

    return valid;
  }

  // Clear error styling on user input
  form.addEventListener('input', function (e) {
    e.target.classList.remove('cs-inquiry-input-error');
  });

  form.addEventListener('submit', async function (e) {
    e.preventDefault();

    if (!validateForm()) {
      if (typeof window.showToast === 'function') {
        window.showToast('Please fill in all required fields correctly.', 'error');
      }
      return;
    }

    var qtyEl = field('quantity');
    var qtyVal = qtyEl && qtyEl.value ? parseInt(qtyEl.value, 10) : null;

    var payload = {
      productId:       field('productId')      ? parseInt(field('productId').value, 10) || null : null,
      customerName:    field('customerName')   ? field('customerName').value.trim()  : '',
      customerEmail:   field('customerEmail')  ? field('customerEmail').value.trim() : '',
      customerPhone:   field('customerPhone')  ? field('customerPhone').value.trim() || null  : null,
      customerCompany: field('customerCompany')? field('customerCompany').value.trim() || null : null,
      quantity:        qtyVal,
      message:         field('message')        ? field('message').value.trim() || null : null,
    };

    submitBtn.disabled    = true;
    submitBtn.textContent = 'Submitting\u2026';

    try {
      var resp = await fetch('/api/inquiry', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(payload)
      });
      var json = await resp.json();

      if (json.success) {
        if (typeof window.showToast === 'function') {
          window.showToast(json.message || 'Inquiry submitted!', 'success');
        }
        form.reset();
        form.querySelectorAll('.cs-inquiry-input-error')
            .forEach(function (el) { el.classList.remove('cs-inquiry-input-error'); });
      } else {
        if (typeof window.showToast === 'function') {
          window.showToast(json.message || 'Failed to submit. Please try again.', 'error');
        }
      }
    } catch (err) {
      if (typeof window.showToast === 'function') {
        window.showToast('Network error. Please check your connection and try again.', 'error');
      }
    } finally {
      submitBtn.disabled    = false;
      submitBtn.textContent = 'Submit Inquiry';
    }
  });
})();
