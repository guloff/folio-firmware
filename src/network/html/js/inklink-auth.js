// Folio pairing for the built-in web pages. Every changing request (upload,
// delete, settings, ...) needs the reader's pairing token: this script adds the
// X-InkLink-Token header to same-origin fetch/XHR calls and an AUTH message to
// upload WebSockets. Without a valid token it asks the reader for a PIN, shows
// a prompt for it and stores the token in localStorage.
(function () {
  'use strict';
  var KEY = 'inklinkToken';
  var HEADER = 'X-InkLink-Token';
  var NAME = 'Web browser';
  var ASK = 'Folio: enter the 6-digit code shown on the reader\nВведите 6-значный код с экрана читалки';
  var RealXHR = window.XMLHttpRequest;
  var validated = false;

  function token() {
    try { return localStorage.getItem(KEY) || ''; } catch (e) { return ''; }
  }
  function store(t) {
    try { if (t) localStorage.setItem(KEY, t); else localStorage.removeItem(KEY); } catch (e) {}
  }
  function changes(method) {
    var m = String(method || 'GET').toUpperCase();
    return m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS';
  }
  function ours(url) {
    try {
      var u = new URL(String(url), location.href);
      return u.origin === location.origin && u.pathname.indexOf('/api/inklink/pair/') !== 0;
    } catch (e) { return false; }
  }
  // Synchronous on purpose: XHR send() and WebSocket send() can't wait for a
  // promise, and the pairing dialog is modal anyway.
  function request(method, url, body, withToken) {
    var x = new RealXHR();
    x.open(method, url, false);
    if (body !== undefined) x.setRequestHeader('Content-Type', 'application/json');
    if (withToken && token()) x.setRequestHeader(HEADER, token());
    try { x.send(body === undefined ? null : JSON.stringify(body)); } catch (e) { return { status: 0, json: {} }; }
    var json = {};
    try { json = JSON.parse(x.responseText); } catch (e) {}
    return { status: x.status, json: json };
  }
  function pair() {
    var r = request('POST', '/api/inklink/pair/start', { name: NAME });
    if (r.status !== 200) {
      alert('Folio: ' + (r.json.error || 'pairing unavailable') +
            (r.json.retryIn ? ' (' + r.json.retryIn + ' s)' : ''));
      return false;
    }
    for (;;) {
      var pin = prompt(ASK);
      if (pin === null) return false;
      r = request('POST', '/api/inklink/pair/finish', { pin: pin.replace(/\D/g, ''), name: NAME });
      if (r.status === 200 && r.json.token) { store(r.json.token); return true; }
      if (r.status !== 403) { alert('Folio: ' + (r.json.error || 'pairing failed')); return false; }
    }
  }
  // Checks the stored token once per page; pairs when it is missing or revoked.
  function ensurePaired() {
    if (validated) return true;
    if (token()) {
      var r = request('GET', '/api/inklink/info', undefined, true);
      if (r.json.pairing && r.json.pairing.authorized) { validated = true; return true; }
      store('');
    }
    validated = pair();
    return validated;
  }

  var realFetch = window.fetch;
  if (realFetch) {
    window.fetch = function (input, init) {
      init = init || {};
      var method = init.method || (input && input.method) || 'GET';
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      if (!changes(method) || !ours(url)) return realFetch.call(window, input, init);
      function attempt() {
        var opts = Object.assign({}, init);
        var headers = new Headers(init.headers || (input && input.headers) || {});
        if (token()) headers.set(HEADER, token());
        opts.headers = headers;
        return realFetch.call(window, input, opts);
      }
      if (!ensurePaired()) return attempt();  // declined: the page shows the 401
      return attempt().then(function (res) {
        if (res.status !== 401) return res;
        store('');
        validated = false;
        return ensurePaired() ? attempt() : res;
      });
    };
  }

  var proto = RealXHR.prototype;
  var realOpen = proto.open;
  var realSend = proto.send;
  proto.open = function (method, url) {
    this._folioMethod = method;
    this._folioUrl = url;
    return realOpen.apply(this, arguments);
  };
  proto.send = function () {
    if (changes(this._folioMethod) && ours(this._folioUrl)) {
      ensurePaired();
      if (token()) this.setRequestHeader(HEADER, token());
      this.addEventListener('load', function () {
        if (this.status === 401) { store(''); validated = false; }
      });
    }
    return realSend.apply(this, arguments);
  };

  if (window.WebSocket) {
    var realWsSend = WebSocket.prototype.send;
    WebSocket.prototype.send = function (data) {
      if (!this._folioAuth) {
        this._folioAuth = true;
        if (typeof data === 'string' && data.indexOf('START:') === 0) ensurePaired();
        if (token()) realWsSend.call(this, 'AUTH:' + token());
      }
      return realWsSend.apply(this, arguments);
    };
  }
})();
