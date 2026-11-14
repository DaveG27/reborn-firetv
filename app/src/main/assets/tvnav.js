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

  // ---- Hand video streams to the native player ----
  // Reborn plays with hls.js (XHR) or <video src>. Report those URLs to the app;
  // the app decides if it's a stream and opens the native player.
  if (window.RebornNative) {
    var report = function (u) {
      try { if (u && /^https?:/i.test(String(u))) window.RebornNative.play(String(u)); } catch (e) {}
    };
    var xo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) { report(u); return xo.apply(this, arguments); };
    if (window.fetch) {
      var fo = window.fetch;
      window.fetch = function (input) {
        report(typeof input === 'string' ? input : (input && input.url));
        return fo.apply(this, arguments);
      };
    }
    var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (d && d.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true, enumerable: d.enumerable, get: d.get,
        set: function (v) { report(v); return d.set.call(this, v); }
      });
    }
    var sa = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function (n, v) {
      if (n === 'src' && (this.tagName === 'VIDEO' || this.tagName === 'SOURCE' || this.tagName === 'AUDIO')) report(v);
      return sa.apply(this, arguments);
    };
  }

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

  // Favourite / star buttons: never the main focus target (Menu button toggles them instead).
  function isFav(el) {
    var s = ((el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className) || '') + ' ' +
            (el.id || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '');
    if (/fav|star|heart/i.test(s)) return true;
    var t = (el.textContent || '').trim();
    return t.length <= 2 && /[★☆♥♡⭐]/.test(t);
  }

  function nearViewport(r) {
    var h = window.innerHeight, w = window.innerWidth;
    return r.bottom > -h && r.top < h * 2 && r.right > -w && r.left < w * 2;
  }

  // Anything the web app made clickable via JS shows a pointer cursor – treat those as targets too.
  function pointerTargets() {
    var out = [];
    var els = document.querySelectorAll('div,li,span,img,article,section,tr,td,p,figure,label');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || !nearViewport(r)) continue;
      if (window.getComputedStyle(el).cursor !== 'pointer') continue;
      // cursor is inherited: only keep the element where pointer *starts*
      var p = el.parentElement;
      if (p && window.getComputedStyle(p).cursor === 'pointer') continue;
      out.push(el);
    }
    return out;
  }

  function candidates() {
    var list = Array.prototype.slice.call(document.querySelectorAll(SELECTOR)).concat(pointerTargets());
    list = list.filter(function (el, i) { return list.indexOf(el) === i && !isFav(el) && visible(el); });
    // Prefer the outermost clickable element (a whole channel row, not an icon inside it).
    return list.filter(function (el) {
      var p = el.parentElement;
      while (p && p !== document.body) {
        if (list.indexOf(p) !== -1 && p.tagName !== 'VIDEO') {
          var pr = p.getBoundingClientRect();
          // ...unless the "parent" is a huge container like the whole list or page
          if (pr.height < window.innerHeight * 0.6 || pr.width < window.innerWidth * 0.6) return false;
        }
        p = p.parentElement;
      }
      return true;
    });
  }

  // Full pointer/mouse/click sequence so it works whatever event the app listens for.
  function press(el) {
    var r = el.getBoundingClientRect();
    var o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
    try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (e) {}
    el.dispatchEvent(new MouseEvent('mousedown', o));
    try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch (e) {}
    el.dispatchEvent(new MouseEvent('mouseup', o));
    el.click();
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
        press(current);
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

  // Called from the Android side for the Menu (☰) button: toggle favourite on the focused row.
  window.__rebornFav = function () {
    if (!current) return false;
    var scope = current;
    for (var depth = 0; scope && depth < 4; depth++, scope = scope.parentElement) {
      var all = scope.querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        if (isFav(all[i]) && visible(all[i])) { press(all[i]); return true; }
      }
    }
    return false;
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
