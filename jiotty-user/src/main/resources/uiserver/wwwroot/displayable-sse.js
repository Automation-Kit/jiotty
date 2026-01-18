(function(global){
  'use strict';

  // Lightweight SSE client for Displayable updates with resilience.
  // Exposes DisplayableSse with methods:
  // - ensureConnected()
  // - subscribe(id, callback) => unsubscribe function
  // - fetchItem(id) => Promise<dto>
  // - setDebug(enabled)

  var eventSource = null;
  var reconnectPending = false;
  var lastActivityTs = 0;
  var listeners = Object.create(null); // id -> Set<fn>
  var lastDtoById = Object.create(null); // id -> last received dto
  var clientIdSeqNum = null; // from server 'hello' event or response header
  var watchdogId = null; // transport-level watchdog timer id
  var lifecycleHandlersInstalled = false; // ensure we add lifecycle handlers once
  var debug = (function(){
    try {
      var sp = new URLSearchParams(global.location ? (global.location.search || '') : '');
      if (sp.has('sseDebug')) return sp.get('sseDebug') !== '0';
      var v = global.localStorage ? global.localStorage.getItem('sseDebug') : null;
      if (v === null) return true; // enable by default to aid diagnostics
      return v === '1' || v === 'true';
    } catch(e) { return true; }
  })();

  function nowIso(){ try { return new Date().toISOString(); } catch(e) { return String(Date.now()); } }
  function log(kind, msg, extra){
    if (!debug) return;
    var cid = clientIdSeqNum != null ? ('[cid='+clientIdSeqNum+']') : '[cid=?]';
    try {
      if (extra!==undefined) console.debug('[SSE]'+cid+'['+nowIso()+']['+kind+'] '+msg, extra);
      else console.debug('[SSE]'+cid+'['+nowIso()+']['+kind+'] '+msg);
    } catch(e){}
  }

  function closeEventSource(kind, message){
    try {
      if (eventSource) {
        log(kind || 'close', message || 'closing EventSource');
        try { eventSource.close(); } catch(_){}
      }
    } finally {
      eventSource = null;
    }
  }

  function scheduleReconnect(delayMs){
    if (reconnectPending) { log('reconnect-skip','already pending'); return; }
    reconnectPending = true;
    var d = (typeof delayMs === 'number') ? delayMs : 2000;
    log('reconnect-schedule','in '+d+'ms');
    setTimeout(function(){
      reconnectPending = false;
      try {
        var rs = eventSource ? eventSource.readyState : 2;
        if (!eventSource || rs !== 1) {
          if (eventSource) { try { eventSource.close(); } catch(_){} eventSource = null; }
          ensureConnected();
        }
      } catch(e) { /* ignore */ }
    }, d);
  }

  function notify(id, dto){
    if (!id) return;
    // cache latest DTO per id so late subscribers get the last known state
    try { lastDtoById[id] = dto; } catch(_){ }
    var set = listeners[id];
    if (!set) return;
    set.forEach(function(fn){
      try { fn(dto, id); } catch(e) { log('listener-error','listener threw for '+id, e); }
    });
  }

  function ensureConnected(){
    try {
      if (eventSource && eventSource.readyState === 1) return eventSource; // OPEN
      if (eventSource) { try { eventSource.close(); } catch(_){} }
      log('create','new EventSource(api/displayables/stream)');
      eventSource = new EventSource('api/displayables/stream');
      eventSource.addEventListener('hello', function(e){
        try {
          var data = JSON.parse(e.data || '{}');
          if (data && typeof data.clientIdSeqNum === 'number') {
            clientIdSeqNum = data.clientIdSeqNum;
            log('hello','clientIdSeqNum=' + clientIdSeqNum);
          } else {
            log('hello','no clientIdSeqNum in payload');
          }
        } catch(err) { log('hello-parse-error','failed to parse hello', err); }
      });
      eventSource.addEventListener('displayable-update', function(e){
        lastActivityTs = Date.now();
        try {
          var data = JSON.parse(e.data || '{}');
          log('update','id='+data.id);
          if (data && data.id) notify(data.id, data.dto);
        } catch(err) { log('parse-error','failed to parse update', err); }
      });
      eventSource.addEventListener('ping', function(){ lastActivityTs = Date.now(); log('ping','received'); });
      eventSource.onopen = function(){ lastActivityTs = Date.now(); log('open','readyState='+ (eventSource ? eventSource.readyState : 'n/a')); };
      eventSource.onerror = function(ev){ log('error','readyState='+ (eventSource ? eventSource.readyState : 'n/a'), ev); scheduleReconnect(3000); };
      return eventSource;
    } catch(e) {
      log('init-failure', String(e));
      scheduleReconnect(5000);
      return null;
    }
  }

  // Page lifecycle handlers and watchdog centralized here
  function startLifecycleHandlers(){
    if (lifecycleHandlersInstalled) return;
    lifecycleHandlersInstalled = true;
    try {
      if (global && global.addEventListener) {
        global.addEventListener('visibilitychange', function(){
          try { log('visibilitychange', (global.document && global.document.hidden) ? 'hidden' : 'visible'); } catch(_){ }
          try { if (global.document && !global.document.hidden) ensureConnected(); } catch(_){ }
        });
        global.addEventListener('pageshow', function(){ log('pageshow','fired'); try { ensureConnected(); } catch(_){ } });
        global.addEventListener('focus', function(){ log('focus','window focused'); try { ensureConnected(); } catch(_){ } });
        global.addEventListener('pagehide', function(){ log('pagehide','closing EventSource and clearing watchdog'); try { if (watchdogId) { clearInterval(watchdogId); } } catch(_){ } watchdogId = null; closeEventSource('close','on pagehide'); lastActivityTs = 0; });
        global.addEventListener('beforeunload', function(){ log('beforeunload','closing EventSource'); closeEventSource('close','on beforeunload'); lastActivityTs = 0; });
      }
    } catch(_){ }
  }

  function startWatchdog(){
    if (watchdogId) return;
    try {
      watchdogId = setInterval(function(){
        try {
          var age = Date.now() - (lastActivityTs || 0);
          var rs = eventSource ? eventSource.readyState : 2;
          if (global.document && global.document.hidden) { log('watchdog','skip: hidden'); return; }
          var stale = age > 30000;
          log('watchdog','tick rs=' + rs + ', stale=' + stale + ', age=' + age + 'ms');
          if (rs !== 1 || stale) {
            closeEventSource('watchdog','forced by watchdog, rs=' + rs + ', age=' + age);
            ensureConnected();
          }
        } catch(_){ }
      }, 7000);
    } catch(_){ }
  }

  function subscribe(id, callback){
    if (!id || typeof callback !== 'function') return function(){};
    var set = listeners[id];
    if (!set) { set = listeners[id] = new Set(); }
    set.add(callback);
    // start connection lazily
    ensureConnected();
    // immediately replay last known dto if available
    try {
      if (Object.prototype.hasOwnProperty.call(lastDtoById, id)) {
        var dto = lastDtoById[id];
        setTimeout(function(){
          try { callback(dto, id); } catch(e) { log('listener-replay-error','listener threw on replay for '+id, e); }
        }, 0);
      }
    } catch(_){ }
    return function unsubscribe(){ try { set.delete(callback); if (set.size === 0) delete listeners[id]; } catch(_){} };
  }

  function fetchItem(id){
    var url = 'api/displayables/item?id=' + encodeURIComponent(id);
    log('fetch','GET ' + url);
    return fetch(url)
      .then(function(r){
        var status = r.status;
        var ct = r.headers && r.headers.get ? r.headers.get('content-type') : null;
        return r.text().then(function(text){
          var obj = null;
          try {
            obj = text ? JSON.parse(text) : null;
          } catch (err) {
            var e = new Error('Failed to parse JSON for '+id+': '+(err && err.message));
            e.name = 'JsonParseError';
            e.status = status;
            e.contentType = ct;
            e.bodySnippet = (text || '').slice(0, 500);
            log('fetch-parse-error', e.message, {status: status, contentType: ct, bodySnippet: e.bodySnippet});
            throw e;
          }
          if (!r.ok) {
            var e2 = new Error('HTTP '+status+' for '+id+': '+(obj && obj.error ? obj.error : (text || '').slice(0, 200)));
            e2.status = status;
            e2.contentType = ct;
            e2.bodySnippet = (text || '').slice(0, 500);
            log('fetch-http-error', e2.message, {status: status, contentType: ct, bodySnippet: e2.bodySnippet});
            throw e2;
          }
          log('fetch-ok','GET '+url+' -> '+status+'; '+(text ? text.length : 0)+' bytes', { contentType: ct });
          return (obj && obj.dto) ? obj.dto : obj;
        });
      })
      .catch(function(err){ log('fetch-fail', 'GET '+url+' failed: '+(err && err.message || err)); throw err; });
  }

  function setDebugEnabled(enabled){ debug = !!enabled; try { if (global.localStorage) localStorage.setItem('sseDebug', debug ? '1' : '0'); } catch(_){} }

  // auto-init a single SSE connection once the page is ready, install lifecycle handlers and watchdog
  try {
    if (global && global.addEventListener) {
      global.addEventListener('DOMContentLoaded', function(){
        try { ensureConnected(); } catch(_){}
        try { startLifecycleHandlers(); } catch(_){}
        try { startWatchdog(); } catch(_){}
      });
    }
  } catch(_){ }

  global.DisplayableSse = {
    ensureConnected: ensureConnected,
    subscribe: subscribe,
    fetchItem: fetchItem,
    setDebug: setDebugEnabled
  };

})(typeof window !== 'undefined' ? window : this);
