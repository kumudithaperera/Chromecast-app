// Scriptlets: small, fixed functions that run before a page's own scripts and stop it from detecting the
// blocker, reading its ad settings or opening ad windows. This browser's own versions, written for it;
// filter lists name them and give their arguments (`site##+js(name, arg, …)`).
// The TV fills in the rules as JSON data (never code) and adds this script, ahead of time, to the sites
// those rules name. Arguments are only ever compared, matched or used as property names; nothing from a
// list is run.
(function () {
  'use strict';
  // { s: { key: [[excludedDomains|null, name, args…], …] }, x: { key: [callKey, …] } }, keys as the TV files them.
  var DATA = __DATA__;
  var host = (location.hostname || '').toLowerCase().replace(/\.$/, '');
  if (!host) return;

  // The same keys as the TV's lookupKeys: the host, each parent domain, and each with its ending as "*".
  var SECOND = /^(ac|co|com|edu|gob|go|gov|ltd|mil|ne|net|nic|nom|or|org|plc|sch)$/;
  function siteOf(h) {
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(h)) return h;
    var L = h.split('.');
    if (L.length <= 2) return h;
    var n = L[L.length - 1].length === 2 && SECOND.test(L[L.length - 2]) ? 3 : 2;
    return L.slice(-n).join('.');
  }
  function hostMatches(h, d) {
    if (d.slice(-2) === '.*') {
      var site = siteOf(h), dot = site.indexOf('.');
      if (dot < 0) return false;
      d = d.slice(0, -1) + site.slice(dot + 1);
    }
    return h === d || (h.length > d.length && h.slice(-d.length - 1) === '.' + d);
  }
  var keys = [], site = siteOf(host), ending = site.indexOf('.') >= 0 ? site.slice(site.indexOf('.') + 1) : '';
  for (var h = host; ;) {
    keys.push(h);
    if (ending && h.length > ending.length + 1 && h.slice(-ending.length - 1) === '.' + ending) keys.push(h.slice(0, -ending.length) + '*');
    var dot = h.indexOf('.');
    if (dot < 0) break;
    h = h.slice(dot + 1);
  }
  var off = Object.create(null), calls = [], seen = Object.create(null);
  keys.forEach(function (k) { (DATA.x[k] || []).forEach(function (c) { off[c] = 1; }); });
  if (off['']) return;
  keys.forEach(function (k) {
    (DATA.s[k] || []).forEach(function (e) {
      var excl = e[0], call = e.slice(1), id = call.join('\u0000');
      if (off[id] || seen[id]) return;
      if (excl && excl.some(function (d) { return hostMatches(host, d); })) return;
      seen[id] = 1;
      calls.push(call);
    });
  });
  if (!calls.length) return;
  var CALLS = calls.slice(0, 60);
  var win = window, doc = document;
  var noop = function () {};
  var fnToString = Function.prototype.toString;

  // "/re/flags" is a regular expression, "" matches everything, anything else is a plain substring.
  function regexOf(s) {
    if (s === undefined || s === '') return null;
    if (s.length > 2 && s.charAt(0) === '/') {
      var end = s.lastIndexOf('/');
      if (end > 0) {
        try { return new RegExp(s.slice(1, end), s.slice(end + 1).replace(/[^imsu]/g, '')); } catch (e) { return /(?!)/; }
      }
    }
    return new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, function (c) { return '\\' + c; }));
  }

  // A leading "!" turns a matcher around.
  function matcher(s) {
    var neg = false;
    s = s === undefined ? '' : String(s);
    if (s.charAt(0) === '!') { neg = true; s = s.slice(1); }
    var re = regexOf(s);
    return function (v) {
      var hit = re === null ? true : re.test(String(v));
      return neg ? !hit : hit;
    };
  }

  function textOf(fn) {
    try {
      if (typeof fn === 'function') return fnToString.call(fn);
      if (fn && typeof fn.handleEvent === 'function') return fnToString.call(fn.handleEvent);
    } catch (e) {}
    return String(fn);
  }

  function isObj(v) { return v !== null && (typeof v === 'object' || typeof v === 'function'); }

  // One trap per not-yet-defined property, shared by every chain that passes through it: a second
  // chain must not replace the first one's ("a.b.x" and "a.b.y" on the same page).
  var traps = new WeakMap();
  function trapFor(owner, prop) {
    var byProp = traps.get(owner);
    if (!byProp) { byProp = Object.create(null); traps.set(owner, byProp); }
    if (byProp[prop]) return byProp[prop];
    var d = Object.getOwnPropertyDescriptor(owner, prop);
    if (d && !d.configurable) return null;
    var t = { cur: d && 'value' in d ? d.value : undefined, next: [] };
    try {
      Object.defineProperty(owner, prop, {
        configurable: true,
        enumerable: true,
        get: function () { return t.cur; },
        set: function (nv) {
          t.cur = nv;
          if (isObj(nv)) t.next.forEach(function (f) { try { f(nv); } catch (e) {} });
        },
      });
    } catch (e) { return null; }
    byProp[prop] = t;
    return t;
  }

  // Calls final(owner, prop) for "a.b.c" once the owner of "c" exists, now or when the page creates it.
  function onChain(path, final) {
    var parts = String(path || '').split('.');
    if (!parts[0]) return;
    (function walk(owner, i) {
      var prop = parts[i];
      if (i === parts.length - 1) { final(owner, prop); return; }
      var v;
      try { v = owner[prop]; } catch (e) { return; }
      if (isObj(v)) { walk(v, i + 1); return; }
      var t = trapFor(owner, prop);
      if (t) t.next.push(function (nv) { walk(nv, i + 1); });
    })(win, 0);
  }

  function define(owner, prop, desc) {
    var d = Object.getOwnPropertyDescriptor(owner, prop);
    if (d && !d.configurable) return false;
    desc.configurable = true;
    try { Object.defineProperty(owner, prop, desc); return true; } catch (e) { return false; }
  }

  function abortError() { return new ReferenceError('Blocked'); }

  // Values set-constant may set. Anything else is ignored.
  function constant(v) {
    switch (v) {
      case 'undefined': return { v: undefined };
      case 'false': return { v: false };
      case 'true': return { v: true };
      case 'null': return { v: null };
      case 'noopFunc': return { v: function () {} };
      case 'trueFunc': return { v: function () { return true; } };
      case 'falseFunc': return { v: function () { return false; } };
      case 'throwFunc': return { v: function () { throw abortError(); } };
      case 'emptyArr': return { v: [] };
      case 'emptyObj': return { v: {} };
      case '': case "''": case '""': case 'emptyStr': return { v: '' };
      case 'yes': case 'no': case 'on': case 'off': case 'ok': case 'accept': case 'accepted': case 'reject': case 'rejected':
        return { v: v };
    }
    if (/^-?\d{1,5}$/.test(v)) {
      var n = parseInt(v, 10);
      if (Math.abs(n) <= 32767) return { v: n };
    }
    return null;
  }

  function delayMatcher(s) {
    if (s === undefined || s === '' || s === '*') return function () { return true; };
    var neg = s.charAt(0) === '!';
    var n = Number(neg ? s.slice(1) : s);
    return function (d) { var hit = Number(d) === n; return neg ? !hit : hit; };
  }

  // Runs fn now, when the page is parsed and as it changes (at most every 100 ms).
  function onDom(fn, attrs) {
    var pending = false;
    function run() { pending = false; try { fn(); } catch (e) {} }
    var mo = new MutationObserver(function () { if (!pending) { pending = true; setTimeout(run, 100); } });
    function start() {
      run();
      var opts = { childList: true, subtree: true };
      if (attrs) { opts.attributes = true; opts.attributeFilter = attrs; }
      if (doc.documentElement) mo.observe(doc.documentElement, opts);
    }
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', start, { once: true });
    else start();
  }

  // "url:/ads/ method:POST" or just "/ads/". null when there is nothing to match: the scriptlet then does nothing.
  function requestMatcher(spec) {
    spec = (spec || '').trim();
    if (!spec) return null;
    var keys = {};
    spec.split(/\s+/).forEach(function (p) {
      var i = p.indexOf(':');
      if (i > 0 && /^[a-z]+$/.test(p.slice(0, i))) keys[p.slice(0, i)] = matcher(p.slice(i + 1));
      else keys.url = matcher(p);
    });
    return function (details) {
      for (var k in keys) if (!keys[k](details[k] === undefined ? '' : details[k])) return false;
      return true;
    };
  }

  function timerDefuser(name, needle, delay) {
    var m = matcher(needle), dm = delayMatcher(delay), orig = win[name];
    if (typeof orig !== 'function') return;
    win[name] = function (fn, d) {
      try { if (m(textOf(fn)) && dm(d)) return orig.call(win, noop, d); } catch (e) {}
      return orig.apply(win, arguments);
    };
  }

  function timerBooster(name, needle, delay, boost) {
    var m = matcher(needle), want = delay === undefined || delay === '' ? 1000 : delay;
    var b = Number(boost);
    if (!(b >= 0.001 && b <= 50)) b = 0.05;
    var dm = want === '*' ? function () { return true; } : function (d) { return Number(d) === Number(want); };
    var orig = win[name];
    if (typeof orig !== 'function') return;
    win[name] = function (fn, d) {
      try { if (m(textOf(fn)) && dm(d)) { var a = Array.prototype.slice.call(arguments); a[1] = Number(d) * b; return orig.apply(win, a); } } catch (e) {}
      return orig.apply(win, arguments);
    };
  }

  function fakeWindow() {
    var w = {
      closed: false, opener: null,
      close: function () { w.closed = true; }, focus: noop, blur: noop, postMessage: noop,
      location: { href: '', assign: noop, replace: noop },
      document: { write: noop, writeln: noop, open: noop, close: noop },
    };
    return w;
  }

  function pathHas(o, parts, i) {
    if (i === parts.length) return true;
    if (!isObj(o)) return false;
    var k = parts[i];
    if (k === '[]' || k === '*') {
      for (var key in o) if (pathHas(o[key], parts, i + 1)) return true;
      return false;
    }
    return Object.prototype.hasOwnProperty.call(o, k) && pathHas(o[k], parts, i + 1);
  }

  function pathDelete(o, parts, i) {
    if (!isObj(o)) return;
    var k = parts[i], last = i === parts.length - 1;
    if (k === '[]' || k === '*') {
      for (var key in o) { if (last) delete o[key]; else pathDelete(o[key], parts, i + 1); }
      return;
    }
    if (!Object.prototype.hasOwnProperty.call(o, k)) return;
    if (last) delete o[k]; else pathDelete(o[k], parts, i + 1);
  }

  var S = {
    'abort-on-property-read': function (path) {
      onChain(path, function (o, p) {
        define(o, p, { get: function () { throw abortError(); }, set: noop });
      });
    },
    'abort-on-property-write': function (path) {
      onChain(path, function (o, p) {
        var cur = o[p];
        define(o, p, { get: function () { return cur; }, set: function () { throw abortError(); } });
      });
    },
    'set-constant': function (path, value) {
      var c = constant(value === undefined ? '' : String(value));
      if (c === null) return;
      onChain(path, function (o, p) {
        if (!define(o, p, { get: function () { return c.v; }, set: noop })) {
          try { o[p] = c.v; } catch (e) {}
        }
      });
    },
    'abort-current-script': function (path, needle) {
      var m = matcher(needle);
      onChain(path, function (o, p) {
        var d = Object.getOwnPropertyDescriptor(o, p);
        var value = d && !d.get ? d.value : undefined;
        var getter = d && d.get, setter = d && d.set;
        function check() {
          var s = doc.currentScript;
          if (!(s instanceof HTMLScriptElement)) return;
          if (m(s.src ? s.src : s.textContent)) throw abortError();
        }
        define(o, p, {
          get: function () { check(); return getter ? getter.call(this) : value; },
          set: function (v) { check(); if (setter) setter.call(this, v); else value = v; },
        });
      });
    },
    'prevent-addEventListener': function (type, handler) {
      var mt = matcher(type), mh = matcher(handler);
      var proto = win.EventTarget && win.EventTarget.prototype;
      if (!proto) return;
      var orig = proto.addEventListener;
      proto.addEventListener = function (t, fn) {
        try { if (mt(t) && mh(textOf(fn))) return; } catch (e) {}
        return orig.apply(this, arguments);
      };
    },
    'no-setTimeout-if': function (needle, delay) { timerDefuser('setTimeout', needle, delay); },
    'no-setInterval-if': function (needle, delay) { timerDefuser('setInterval', needle, delay); },
    'nano-setTimeout-booster': function (needle, delay, boost) { timerBooster('setTimeout', needle, delay, boost); },
    'nano-setInterval-booster': function (needle, delay, boost) { timerBooster('setInterval', needle, delay, boost); },
    'no-window-open-if': function (needle) {
      var m = matcher(needle), orig = win.open;
      if (typeof orig !== 'function') return;
      win.open = function (url) {
        try { if (m(url === undefined ? '' : String(url))) return fakeWindow(); } catch (e) {}
        return orig.apply(win, arguments);
      };
    },
    'no-xhr-if': function (spec) {
      var match = requestMatcher(spec);
      if (!match || !win.XMLHttpRequest) return;
      var P = XMLHttpRequest.prototype, open = P.open, send = P.send, hit = new WeakSet();
      P.open = function (method, url) {
        try { if (match({ method: String(method).toUpperCase(), url: String(url) })) hit.add(this); else hit.delete(this); } catch (e) {}
        return open.apply(this, arguments);
      };
      P.send = function () {
        if (!hit.has(this)) return send.apply(this, arguments);
        var xhr = this, empty = xhr.responseType === '' || xhr.responseType === 'text' ? '' : null;
        try {
          Object.defineProperties(xhr, {
            readyState: { value: 4, configurable: true }, status: { value: 200, configurable: true },
            statusText: { value: 'OK', configurable: true }, responseText: { value: '', configurable: true },
            response: { value: empty, configurable: true }, responseURL: { value: '', configurable: true },
          });
        } catch (e) {}
        setTimeout(function () {
          ['readystatechange', 'load', 'loadend'].forEach(function (t) { try { xhr.dispatchEvent(new Event(t)); } catch (e) {} });
        }, 1);
      };
    },
    'no-fetch-if': function (spec) {
      var match = requestMatcher(spec), orig = win.fetch;
      if (!match || typeof orig !== 'function') return;
      win.fetch = function (input, init) {
        try {
          var url = typeof input === 'string' ? input : (input && input.url) || String(input);
          var method = (init && init.method) || (input && input.method) || 'GET';
          if (match({ url: url, method: String(method).toUpperCase() })) return Promise.resolve(new Response('', { status: 200, statusText: 'OK' }));
        } catch (e) {}
        return orig.apply(win, arguments);
      };
    },
    'remove-attr': function (attrs, selector) {
      var names = String(attrs || '').split('|').map(function (s) { return s.trim(); }).filter(Boolean);
      if (!names.length) return;
      var sel = selector || names.map(function (n) { return '[' + n + ']'; }).join(',');
      onDom(function () {
        doc.querySelectorAll(sel).forEach(function (e) { names.forEach(function (n) { e.removeAttribute(n); }); });
      }, names);
    },
    'remove-class': function (classes, selector) {
      var names = String(classes || '').split('|').map(function (s) { return s.trim(); }).filter(Boolean);
      if (!names.length) return;
      var sel = selector || names.map(function (n) { return '.' + n; }).join(',');
      onDom(function () {
        doc.querySelectorAll(sel).forEach(function (e) { names.forEach(function (n) { e.classList.remove(n); }); });
      }, ['class']);
    },
    'json-prune': function (prune, needs) {
      var paths = String(prune || '').split(/\s+/).filter(Boolean).map(function (p) { return p.split('.'); });
      if (!paths.length) return;
      var required = String(needs || '').split(/\s+/).filter(Boolean).map(function (p) { return p.split('.'); });
      function clean(o) {
        if (!isObj(o)) return o;
        if (required.length && !required.every(function (r) { return pathHas(o, r, 0); })) return o;
        paths.forEach(function (p) { pathDelete(o, p, 0); });
        return o;
      }
      var parse = JSON.parse;
      JSON.parse = function () {
        var r = parse.apply(this, arguments);
        try { return clean(r); } catch (e) { return r; }
      };
      var R = win.Response && win.Response.prototype, json = R && R.json;
      if (json) R.json = function () { return json.apply(this, arguments).then(function (r) { try { return clean(r); } catch (e) { return r; } }); };
    },
    'noeval': function () { win.eval = function () {}; },
    'set-local-storage-item': function (key, value) {
      if (!/^[\w.:\-]{1,100}$/.test(String(key || ''))) return;
      try {
        if (value === '$remove$') { win.localStorage.removeItem(key); return; }
        var c = constant(String(value === undefined ? '' : value));
        if (c === null) return;
        win.localStorage.setItem(key, String(c.v));
      } catch (e) {}
    },
  };

  for (var i = 0; i < CALLS.length; i++) {
    var call = CALLS[i], f = S[call[0]];
    if (f) { try { f.apply(null, call.slice(1)); } catch (e) {} }
  }
})();
