(function () {
  'use strict';

  var wrap   = document.querySelector('.cs-coverflow-wrap');
  if (!wrap) return;

  var slides  = Array.from(wrap.querySelectorAll('.cs-cf-slide'));
  var dots    = Array.from(document.querySelectorAll('.cs-cf-dot'));
  var prevBtn = document.getElementById('csCfPrev');
  var nextBtn = document.getElementById('csCfNext');

  if (slides.length === 0) return;

  var current   = 0;
  var autoTimer = null;

  function update() {
    slides.forEach(function (slide, i) {
      slide.classList.remove('cs-cf-active', 'cs-cf-prev', 'cs-cf-next', 'cs-cf-hidden');
      var diff = i - current;
      var total = slides.length;
      // Normalize diff to range [-total/2, total/2]
      if (diff > total / 2)  diff -= total;
      if (diff < -total / 2) diff += total;

      if (diff === 0)       slide.classList.add('cs-cf-active');
      else if (diff === -1) slide.classList.add('cs-cf-prev');
      else if (diff === 1)  slide.classList.add('cs-cf-next');
      else                  slide.classList.add('cs-cf-hidden');
    });

    dots.forEach(function (dot, i) {
      dot.classList.toggle('cs-cf-dot-active', i === current);
      dot.setAttribute('aria-selected', i === current ? 'true' : 'false');
    });
  }

  function goTo(index) {
    current = ((index % slides.length) + slides.length) % slides.length;
    update();
  }

  function next() { goTo(current + 1); }
  function prev() { goTo(current - 1); }

  function startAuto() {
    stopAuto();
    autoTimer = setInterval(next, 5000);
  }
  function stopAuto() { clearInterval(autoTimer); }

  // Arrow buttons
  if (prevBtn) prevBtn.addEventListener('click', function () { prev(); startAuto(); });
  if (nextBtn) nextBtn.addEventListener('click', function () { next(); startAuto(); });

  // Dot clicks
  dots.forEach(function (dot, i) {
    dot.addEventListener('click', function () { goTo(i); startAuto(); });
  });

  // Click prev/next slides to navigate; click active slide to follow link
  slides.forEach(function (slide, i) {
    slide.addEventListener('click', function () {
      if (slide.classList.contains('cs-cf-prev')) {
        prev(); startAuto();
      } else if (slide.classList.contains('cs-cf-next')) {
        next(); startAuto();
      } else if (slide.classList.contains('cs-cf-active')) {
        var link = slide.getAttribute('data-link');
        if (link) window.location.href = link;
      }
    });
  });

  // Pause on hover
  wrap.addEventListener('mouseenter', stopAuto);
  wrap.addEventListener('mouseleave', startAuto);

  // Touch swipe
  var touchStartX = 0;
  wrap.addEventListener('touchstart', function (e) {
    touchStartX = e.touches[0].clientX;
  }, { passive: true });
  wrap.addEventListener('touchend', function (e) {
    var dx = e.changedTouches[0].clientX - touchStartX;
    if (Math.abs(dx) > 50) { dx < 0 ? next() : prev(); startAuto(); }
  }, { passive: true });

  // Init
  update();
  if (slides.length > 1) startAuto();
})();
