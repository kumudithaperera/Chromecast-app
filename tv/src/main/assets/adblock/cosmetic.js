// Element hiding, in every frame. Fixed code: the TV only ever answers with CSS that hides things.
// 1. Asks the TV for this frame's CSS ("H"): the site's own rules and the generic ones that aren't
//    plain class or id selectors.
// 2. Reports the class names and ids the page uses ("T .a #b"), now and as the page changes; the TV
//    answers with the generic rules for those names. This is how uBlock Origin keeps tens of thousands
//    of generic rules out of every page.
// The CSS goes into a constructed style sheet, which a page's Content-Security-Policy doesn't block.
(function () {
  if (window.__tvAdsInit) return;
  window.__tvAdsInit = true;
  var bridge = window.tvAdblock;
  if (!bridge) return;
  var css = '', sheet = null, styleEl = null;
  var sent = Object.create(null), sentCount = 0, queue = [], timer = 0;
  var NAME = /^[A-Za-z_][A-Za-z0-9_-]{0,99}$/;
  var MAX_NAMES = 5000;

  function attach() {
    var d = document;
    try {
      if (!sheet) sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      if (d.adoptedStyleSheets.indexOf(sheet) < 0) d.adoptedStyleSheets = d.adoptedStyleSheets.concat([sheet]);
      return;
    } catch (e) {}
    // Older engines: a plain style element.
    try {
      if (!styleEl) styleEl = d.createElement('style');
      styleEl.textContent = css;
      if (!styleEl.parentNode && d.documentElement) d.documentElement.appendChild(styleEl);
    } catch (e) {}
  }

  bridge.onmessage = function (e) {
    var data = e.data;
    if (typeof data !== 'string' || data.charAt(0) !== 'C') return;
    css += data.slice(1);
    attach();
  };
  bridge.postMessage('H');

  function add(prefix, name) {
    if (sentCount >= MAX_NAMES || !NAME.test(name)) return;
    var t = prefix + name;
    if (sent[t]) return;
    sent[t] = 1;
    sentCount++;
    queue.push(t);
  }

  function look(el) {
    if (el.id) add('#', el.id);
    var c = el.classList;
    if (c) for (var i = 0; i < c.length; i++) add('.', c[i]);
  }

  function scan(root) {
    if (!root || root.nodeType !== 1) return;
    look(root);
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) look(all[i]);
  }

  function flush() {
    timer = 0;
    if (!queue.length) return;
    bridge.postMessage('T ' + queue.splice(0, 400).join(' '));
    if (queue.length) timer = setTimeout(flush, 50);
  }

  function later() {
    if (!timer) timer = setTimeout(flush, 100);
  }

  var added = [], scanPending = false;
  var observer = new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') look(r.target);
      else for (var j = 0; j < r.addedNodes.length; j++) added.push(r.addedNodes[j]);
    }
    // Big pages add thousands of nodes; scanning them in one go later is cheaper than one by one now.
    if (added.length && !scanPending) {
      scanPending = true;
      setTimeout(function () {
        scanPending = false;
        var nodes = added;
        added = [];
        for (var k = 0; k < nodes.length && sentCount < MAX_NAMES; k++) scan(nodes[k]);
        later();
      }, 100);
    } else {
      later();
    }
  });

  function start() {
    var root = document.documentElement;
    if (!root) return;
    scan(root);
    later();
    observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
  // The style sheet list belongs to the document; re-attach once the page has replaced it.
  document.addEventListener('DOMContentLoaded', function () { if (css) attach(); }, { once: true });
})();
