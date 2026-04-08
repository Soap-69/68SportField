/**
 * Product image gallery — thumbnail click swaps main image.
 * Keyboard: ArrowLeft / ArrowRight to cycle.
 */
(function () {
  'use strict';

  const mainImg = document.getElementById('pgMainImage');
  const thumbs  = document.querySelectorAll('.cs-pg-thumb');

  if (!mainImg || thumbs.length === 0) return;

  function setActive(thumb) {
    thumbs.forEach(t => t.classList.remove('cs-pg-thumb-active'));
    thumb.classList.add('cs-pg-thumb-active');
    mainImg.src = thumb.dataset.fullSrc;
    mainImg.alt = thumb.querySelector('img')?.alt || '';
  }

  thumbs.forEach(thumb => {
    thumb.addEventListener('click', () => setActive(thumb));
  });

  // Keyboard navigation
  document.addEventListener('keydown', e => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    const active = document.querySelector('.cs-pg-thumb-active');
    const all    = [...thumbs];
    const idx    = active ? all.indexOf(active) : 0;
    if (e.key === 'ArrowRight' && idx < all.length - 1) setActive(all[idx + 1]);
    if (e.key === 'ArrowLeft'  && idx > 0)               setActive(all[idx - 1]);
  });

})();
