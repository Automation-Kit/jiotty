(function(global){
  'use strict';

  // Lightweight SSE client for Displayable updates with resilience.
  // Exposes DisplayableSse with methods:
  // - ensureConnected()
  // - subscribe(id, callback) => unsubscribe function
  // - fetchItem(id) => Promise<dto>
  // - setDebug(enabled)

  let eventSource = null;
  let reconnectPending = false;
  let lastActivityTs = 0;
  const listeners = Object.create(null); // id -> Set<fn>
  const lastDtoById = Object.create(null); // id -> last received dto
  let clientIdSeqNum = null;
  let watchdogId = null;
  let lifecycleHandlersInstalled = false;
  let debug = (function () {
    try {
      const sp = new URLSearchParams(global.location ? (global.location.search || '') : '');
      if (sp.has('sseDebug')) return sp.get('sseDebug') !== '0';
      const v = global.localStorage ? global.localStorage.getItem('sseDebug') : null;
      if (v === null) return true;
      return v === '1' || v === 'true';
    } catch(e) { return true; }
  })();

  function nowIso(){ try { return new Date().toISOString(); } catch(e) { return String(Date.now()); } }
  function log(kind, msg, extra){
    if (!debug) return;
    const cid = clientIdSeqNum != null ? ('[cid=' + clientIdSeqNum + ']') : '[cid=?]';
    if (extra !== undefined) console.debug('[SSE]' + cid + '[' + nowIso() + '][' + kind + '] ' + msg, extra);
    else console.debug('[SSE]' + cid + '[' + nowIso() + '][' + kind + '] ' + msg);
  }

  function closeEventSource(kind, message){
    if (!eventSource) return;
    log(kind || 'close', message || 'closing EventSource');
    try {
      eventSource.close();
    } catch (_) {
    }
    eventSource = null;
  }

  function scheduleReconnect(delayMs){
    if (reconnectPending) return;
    reconnectPending = true;
    const d = (typeof delayMs === 'number') ? delayMs : 2000;
    log('reconnect-schedule','in '+d+'ms');
    setTimeout(function(){
      reconnectPending = false;
      const rs = eventSource ? eventSource.readyState : 2;
      if (!eventSource || rs !== 1) {
        if (eventSource) {
          try {
            eventSource.close();
          } catch (_) {
          }
          eventSource = null;
        }
        ensureConnected();
      }
    }, d);
  }

  function notify(id, dto){
    if (!id) return;
    // cache latest DTO per id so late subscribers get the last known state
    lastDtoById[id] = dto;
    const set = listeners[id];
    if (!set) return;
    set.forEach(function(fn){
      try { fn(dto, id); } catch(e) { log('listener-error','listener threw for '+id, e); }
    });
  }

  function ensureConnected(){
    if (eventSource && eventSource.readyState === 1) return eventSource;
    if (eventSource) {
      try {
        eventSource.close();
      } catch (_) {
      }
    }
    log('create', 'new EventSource(api/displayables/stream)');
    try {
      eventSource = new EventSource('api/displayables/stream');
    } catch (e) {
      log('init-failure', String(e));
      scheduleReconnect(5000);
      return null;
    }
    eventSource.addEventListener('hello', function (e) {
      try {
        const data = JSON.parse(e.data || '{}');
        if (data && typeof data.clientIdSeqNum === 'number') {
          clientIdSeqNum = data.clientIdSeqNum;
          log('hello', 'clientIdSeqNum=' + clientIdSeqNum);
        }
      } catch (err) {
        log('hello-parse-error', 'failed to parse hello', err);
      }
    });
    eventSource.addEventListener('displayable-update', function (e) {
      lastActivityTs = Date.now();
      try {
        const data = JSON.parse(e.data || '{}');
        log('update', 'id=' + data.id);
        if (data && data.id) notify(data.id, data.dto);
      } catch (err) {
        log('parse-error', 'failed to parse update', err);
      }
    });
    eventSource.addEventListener('ping', function () {
      lastActivityTs = Date.now();
    });
    eventSource.onopen = function () {
      lastActivityTs = Date.now();
      log('open', 'readyState=' + (eventSource ? eventSource.readyState : 'n/a'));
    };
    eventSource.onerror = function (ev) {
      log('error', 'readyState=' + (eventSource ? eventSource.readyState : 'n/a'), ev);
      scheduleReconnect(3000);
    };
    return eventSource;
  }

  function startLifecycleHandlers(){
    if (lifecycleHandlersInstalled) return;
    lifecycleHandlersInstalled = true;
    if (!global || !global.addEventListener) return;
    global.addEventListener('visibilitychange', function () {
      log('visibilitychange', (global.document && global.document.hidden) ? 'hidden' : 'visible');
      if (global.document && !global.document.hidden) ensureConnected();
    });
    global.addEventListener('pageshow', function () {
      log('pageshow', 'fired');
      ensureConnected();
    });
    global.addEventListener('focus', function () {
      log('focus', 'window focused');
      ensureConnected();
    });
    global.addEventListener('pagehide', function () {
      log('pagehide', 'closing EventSource and clearing watchdog');
      if (watchdogId) clearInterval(watchdogId);
      watchdogId = null;
      closeEventSource('close', 'on pagehide');
      lastActivityTs = 0;
    });
    global.addEventListener('beforeunload', function () {
      log('beforeunload', 'closing EventSource');
      closeEventSource('close', 'on beforeunload');
      lastActivityTs = 0;
    });
  }

  function startWatchdog(){
    if (watchdogId) return;
    watchdogId = setInterval(function () {
      const age = Date.now() - (lastActivityTs || 0);
      const rs = eventSource ? eventSource.readyState : 2;
      if (global.document && global.document.hidden) return;
      const stale = age > 30000;
      log('watchdog', 'tick rs=' + rs + ', stale=' + stale + ', age=' + age + 'ms');
      if (rs !== 1 || stale) {
        closeEventSource('watchdog', 'forced by watchdog, rs=' + rs + ', age=' + age);
        ensureConnected();
      }
    }, 7000);
  }

  function subscribe(id, callback){
    if (!id || typeof callback !== 'function') return function(){};
    let set = listeners[id];
    if (!set) { set = listeners[id] = new Set(); }
    set.add(callback);
    ensureConnected();
    // immediately replay last known dto if available so late subscribers see current state
    if (Object.prototype.hasOwnProperty.call(lastDtoById, id)) {
      const dto = lastDtoById[id];
      setTimeout(function () {
        try {
          callback(dto, id);
        } catch (e) {
          log('listener-replay-error', 'listener threw on replay for ' + id, e);
        }
      }, 0);
    }
    return function unsubscribe() {
      set.delete(callback);
      if (set.size === 0) delete listeners[id];
    };
  }

  function fetchItem(id){
    const url = 'api/displayables/item?id=' + encodeURIComponent(id);
    log('fetch','GET ' + url);
    return fetch(url)
      .then(function(r){
        const status = r.status;
        const ct = r.headers && r.headers.get ? r.headers.get('content-type') : null;
        return r.text().then(function(text){
          let obj = null;
          try {
            obj = text ? JSON.parse(text) : null;
          } catch (err) {
            const e = new Error('Failed to parse JSON for ' + id + ': ' + (err && err.message));
            e.status = status;
            e.contentType = ct;
            log('fetch-parse-error', e.message, {status: status, contentType: ct});
            throw e;
          }
          if (!r.ok) {
            const e2 = new Error('HTTP ' + status + ' for ' + id + ': ' + (obj && obj.error ? obj.error : (text || '').slice(0, 200)));
            e2.status = status;
            log('fetch-http-error', e2.message, {status: status, contentType: ct});
            throw e2;
          }
          log('fetch-ok', 'GET ' + url + ' -> ' + status + '; ' + (text ? text.length : 0) + ' bytes');
          return (obj && obj.dto) ? obj.dto : obj;
        });
      })
      .catch(function(err){ log('fetch-fail', 'GET '+url+' failed: '+(err && err.message || err)); throw err; });
  }

  function setDebugEnabled(enabled){ debug = !!enabled; try { if (global.localStorage) localStorage.setItem('sseDebug', debug ? '1' : '0'); } catch(_){} }

  // Auto-init on DOMContentLoaded
  if (global && global.addEventListener) {
    global.addEventListener('DOMContentLoaded', function () {
      ensureConnected();
      startLifecycleHandlers();
      startWatchdog();
    });
  }

  global.DisplayableSse = {
    ensureConnected: ensureConnected,
    subscribe: subscribe,
    fetchItem: fetchItem,
    setDebug: setDebugEnabled
  };

})(typeof window !== 'undefined' ? window : this);
