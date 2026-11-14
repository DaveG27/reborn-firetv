/*
 * Reborn TV navigation layer.
 * Injected into the web app on every page load so the Fire TV remote works:
 *  - D-pad moves a visible focus ring between clickable things (spatial navigation)
 *  - OK/Select clicks the focused item
 *  - Media keys control the playing video
 * The web app itself doesn't need any changes.
 */
(function () {
  if (window.__rebornTV) return;
  window.__rebornTV = true;

  var SELECTOR = [
    'a[href]', 'button', 'input', 'select', 'textarea', 'video',
    '[role="button"]', '[role="link"]', '[role="tab"]', '[role="option"]',
    '[onclick]', '[tabindex]:not([tabindex="-1"])',
    '.channel', '.card', '.item', '.tile', 'li[data-id]', '[data-stream-id]'
  ].join(',');

  // Focus ring styling – big and obvious from the sofa.
  var style = document.createElement('style');
  style.textContent =
    '.rtv-focus{outline:4px solid #E50914 !important;outline-offset:2px !important;' +
    'box-shadow:0 0 0 6px rgba(229,9,20,.35) !important;border-radius:6px;' +
    'transform:scale(1.03);transition:transform .08s ease;position:relative;z-index:2}' +
    'html{cursor:none}';
  (document.head || document.documentElement).appendChild(style);

  var current = null;

  function visible(el) {
    if (!el || el.disabled) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var cs = window.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || cs.opacity === '0') return false;
    return true;
  }

  function candidates() {
    var list = Array.prototype.slice.call(document.querySelectorAll(SELECTOR));
    // Prefer the outermost clickable element (avoid focusing an icon inside a button).
    return list.filter(function (el) {
      if (!visible(el)) return false;
      var p = el.parentElement;
      while (p && p !== document.body) {
        if (list.indexOf(p) !== -1 && visible(p) && p.tagName !== 'VIDEO') return false;
        p = p.parentElement;
      }
      return true;
    });
  }

  function setFocus(el) {
    if (!el) return;
    if (current) current.classList.remove('rtv-focus');
    current = el;
    el.classList.add('rtv-focus');
    if (!el.hasAttribute('tabindex') && !/^(A|BUTTON|INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) {
      el.setAttribute('tabindex', '-1');
    }
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function move(dir) {
    var list = candidates();
    if (!list.length) return false;
    if (!current || !document.contains(current) || !visible(current)) {
      setFocus(list[0]);
      return true;
    }
    var from = current.getBoundingClientRect();
    var c = center(from);
    var best = null, bestScore = Infinity;
    list.forEach(function (el) {
      if (el === current) return;
      var r = el.getBoundingClientRect();
      var t = center(r);
      var dx = t.x - c.x, dy = t.y - c.y;
      var primary, cross;
      if (dir === 'left')  { if (r.right > from.left + 1) return; primary = -dx; cross = Math.abs(dy); }
      if (dir === 'right') { if (r.left < from.right - 1) return; primary = dx;  cross = Math.abs(dy); }
      if (dir === 'up')    { if (r.bottom > from.top + 1) return; primary = -dy; cross = Math.abs(dx); }
      if (dir === 'down')  { if (r.top < from.bottom - 1) return; primary = dy;  cross = Math.abs(dx); }
      var score = primary + cross * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (best) { setFocus(best); return true; }
    return false;
  }

  function activeVideo() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) if (visible(vids[i])) return vids[i];
    return vids[0] || null;
  }

  function isTyping(el) {
    return el && (el.tagName === 'TEXTAREA' ||
      (el.tagName === 'INPUT' && !/^(button|submit|checkbox|radio|range)$/i.test(el.type)));
  }

  document.addEventListener('keydown', function (e) {
    var k = e.key;
    var active = document.activeElement;

    // While typing in a text box let left/right move the cursor; up/down leave the box.
    if (isTyping(active) && (k === 'ArrowLeft' || k === 'ArrowRight')) return;

    var dirs = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
    if (dirs[k]) {
      // Full-screen video with nothing else to move to: left/right seek 10s.
      var v = activeVideo();
      if (v && document.fullscreenElement && (k === 'ArrowLeft' || k === 'ArrowRight')) {
        v.currentTime = Math.max(0, v.currentTime + (k === 'ArrowRight' ? 10 : -10));
        e.preventDefault();
        return;
      }
      if (move(dirs[k])) e.preventDefault();
      return;
    }

    if (k === 'Enter' && current && document.contains(current)) {
      if (current.tagName === 'VIDEO') {
        current.paused ? current.play() : current.pause();
        e.preventDefault();
      } else if (!isTyping(current) && current.tagName !== 'SELECT') {
        current.click();
        e.preventDefault();
      }
    }
  }, true);

  // Keep the ring in sync if the page moves focus itself (e.g. after login).
  document.addEventListener('focusin', function (e) {
    if (e.target && e.target !== current && e.target.matches && e.target.matches(SELECTOR)) {
      if (current) current.classList.remove('rtv-focus');
      current = e.target;
      current.classList.add('rtv-focus');
    }
  });

  // Called from the Android side for remote media buttons.
  window.__rebornMedia = function (action) {
    var v = activeVideo();
    if (!v) return false;
    if (action === 'playpause') v.paused ? v.play() : v.pause();
    if (action === 'play') v.play();
    if (action === 'pause') v.pause();
    if (action === 'ff') v.currentTime += 30;
    if (action === 'rw') v.currentTime = Math.max(0, v.currentTime - 30);
    return true;
  };

  // Called from the Android side for the Back button. Returns true if handled here.
  window.__rebornBack = function () {
    if (document.fullscreenElement) { document.exitFullscreen(); return true; }
    // Let the web app close any open modal/player with Escape.
    var ev = new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true, cancelable: true });
    var notCancelled = (document.activeElement || document.body).dispatchEvent(ev);
    return !notCancelled; // app called preventDefault => it handled Escape
  };

  // Give the first item focus once the page has drawn.
  setTimeout(function () { if (!current) move('down'); }, 600);
  new MutationObserver(function () {
    if (!current || !document.contains(current)) { current = null; setTimeout(function () { if (!current) move('down'); }, 300); }
  }).observe(document.documentElement, { childList: true, subtree: true });
})();
