(function () {
  'use strict';

  /**
   * Initialises a single carousel track.
   * @param {HTMLElement} track - the .cs-product-carousel element
   */
  function initCarousel(track) {
    var wrap    = track.closest('.cs-carousel-wrap');
    if (!wrap) return;

    var prevBtn = wrap.querySelector('.cs-carousel-arrow-prev');
    var nextBtn = wrap.querySelector('.cs-carousel-arrow-next');

    /* ── Measure one card-width + gap ───────────────────────── */
    function getScrollUnit() {
      var item = track.querySelector('.cs-carousel-item');
      if (!item) return 300;
      var style   = window.getComputedStyle(track);
      var gapStr  = style.columnGap || style.gap || '16px';
      var gap     = parseFloat(gapStr) || 16;
      return item.offsetWidth + gap;
    }

    /* ── Show / hide arrows based on scroll position ─────────── */
    function updateArrows() {
      if (!prevBtn && !nextBtn) return;
      var sl      = track.scrollLeft;
      var maxSl   = track.scrollWidth - track.clientWidth;
      var atStart = sl <= 2;
      var atEnd   = sl >= maxSl - 2;

      if (prevBtn) prevBtn.classList.toggle('cs-carousel-arrow-hidden', atStart);
      if (nextBtn) nextBtn.classList.toggle('cs-carousel-arrow-hidden', atEnd);
    }

    /* ── Arrow click handlers ────────────────────────────────── */
    if (prevBtn) {
      prevBtn.addEventListener('click', function () {
        track.scrollBy({ left: -getScrollUnit(), behavior: 'smooth' });
      });
    }

    if (nextBtn) {
      nextBtn.addEventListener('click', function () {
        track.scrollBy({ left: getScrollUnit(), behavior: 'smooth' });
      });
    }

    /* ── Update arrows on scroll ─────────────────────────────── */
    track.addEventListener('scroll', updateArrows, { passive: true });

    /* ── Update on resize (breakpoint changes card width) ─────── */
    window.addEventListener('resize', updateArrows, { passive: true });

    /* ── Initial arrow state ─────────────────────────────────── */
    updateArrows();
  }

  /* ── Wire up all carousels on the page ──────────────────────── */
  document.querySelectorAll('.cs-product-carousel').forEach(initCarousel);

})();
