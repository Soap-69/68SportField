'use strict';
(function () {

  var cards = document.querySelectorAll('.cs-variant-pill');
  var priceDisplay = document.getElementById('variantPriceDisplay');
  var stockStatus  = document.getElementById('variantStockStatus');
  var addBtn       = document.getElementById('addToCartBtn');
  var qtyInput     = document.getElementById('qtyInput');
  var qtyMinus     = document.getElementById('qtyMinus');
  var qtyPlus      = document.getElementById('qtyPlus');
  var selectedVariantId = null;

  function formatMoney(n) {
    return '$' + parseFloat(n).toLocaleString('en-US', {minimumFractionDigits:2, maximumFractionDigits:2});
  }

  function renderPrice(card) {
    var price     = card.dataset.price;
    var salePrice = card.dataset.salePrice;
    if (!priceDisplay) return;
    if (salePrice && salePrice !== '') {
      priceDisplay.innerHTML =
        '<span class="cs-pd-price-original" style="text-decoration:line-through;opacity:0.6;margin-right:8px;">' + formatMoney(price) + '</span>' +
        '<span class="cs-pd-price cs-pd-price-sale">' + formatMoney(salePrice) + '</span>';
    } else {
      priceDisplay.innerHTML = '<span class="cs-pd-price">' + formatMoney(price) + '</span>';
    }
  }

  function renderStock(card) {
    var stock = parseInt(card.dataset.stock || '0');
    if (!stockStatus || !addBtn || !qtyInput) return;
    if (stock <= 0) {
      stockStatus.innerHTML = '<span style="color:#ff3b30;font-weight:500;">Out of Stock</span>';
      addBtn.disabled = true;
      addBtn.textContent = 'Out of Stock';
      qtyInput.max = 0;
    } else if (stock <= 10) {
      stockStatus.innerHTML = '<span style="color:#ff9500;font-weight:500;">Only ' + stock + ' left</span>';
      addBtn.disabled = false;
      addBtn.textContent = 'Add to Cart';
      qtyInput.max = stock;
    } else {
      stockStatus.innerHTML = '<span style="color:#34c759;font-weight:500;">In Stock</span>';
      addBtn.disabled = false;
      addBtn.textContent = 'Add to Cart';
      qtyInput.max = stock;
    }
    if (parseInt(qtyInput.value) > stock) qtyInput.value = Math.max(1, stock);
  }

  function selectCard(card) {
    cards.forEach(function(c) { c.classList.remove('cs-variant-pill-selected'); });
    card.classList.add('cs-variant-pill-selected');
    selectedVariantId = card.dataset.variantId;
    renderPrice(card);
    renderStock(card);
  }

  cards.forEach(function(card) {
    card.addEventListener('click', function() { selectCard(card); });
  });

  // Auto-select first card
  if (cards.length > 0) selectCard(cards[0]);

  // Qty controls
  if (qtyMinus) qtyMinus.addEventListener('click', function() {
    var v = parseInt(qtyInput.value);
    if (v > 1) qtyInput.value = v - 1;
  });
  if (qtyPlus) qtyPlus.addEventListener('click', function() {
    var v = parseInt(qtyInput.value);
    var max = parseInt(qtyInput.max || '99');
    if (v < max) qtyInput.value = v + 1;
  });

  // Add to cart
  if (addBtn) addBtn.addEventListener('click', function() {
    if (!selectedVariantId) return;
    var qty = parseInt(qtyInput.value) || 1;
    addBtn.disabled = true;
    addBtn.textContent = 'Adding\u2026';

    window.csrfPost('/api/cart/add', { variantId: parseInt(selectedVariantId), quantity: qty })
    .then(function(data) {
      if (data.success) {
        addBtn.textContent = 'Added!';
        setTimeout(function() {
          addBtn.disabled = false;
          addBtn.textContent = 'Add to Cart';
        }, 1500);
        // Update navbar badge
        var badge = document.getElementById('csCartBadge');
        if (badge) {
          badge.textContent = data.cartItemCount;
          badge.style.display = data.cartItemCount > 0 ? 'flex' : 'none';
        }
        if (typeof window.showToast === 'function') {
          window.showToast('Added to cart!', 'success');
        }
      } else {
        addBtn.disabled = false;
        addBtn.textContent = 'Add to Cart';
        if (typeof window.showToast === 'function') {
          window.showToast(data.message || 'Could not add to cart.', 'error');
        }
      }
    })
    .catch(function() {
      addBtn.disabled = false;
      addBtn.textContent = 'Add to Cart';
      if (typeof window.showToast === 'function') {
        window.showToast('Error connecting to server.', 'error');
      }
    });
  });

})();
