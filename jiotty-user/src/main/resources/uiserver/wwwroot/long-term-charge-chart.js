(function(global){
  'use strict';

  // Debug logging toggle for ChargeChart
  var chartDebug = (function(){
    try {
      var sp = new URLSearchParams(global.location ? (global.location.search || '') : '');
      if (sp.has('chartDebug')) return sp.get('chartDebug') !== '0';
      var v = global.localStorage ? global.localStorage.getItem('chartDebug') : null;
      if (v === null) return true; // enable by default to aid diagnostics
      return v === '1' || v === 'true';
    } catch(e) { return true; }
  })();
  function chartNowIso(){ try { return new Date().toISOString(); } catch(e) { return String(Date.now()); } }
  function clog(kind, msg, extra){
    if (!chartDebug) return;
    try {
      if (extra !== undefined) console.debug('[ChargeChart]['+chartNowIso()+']['+kind+'] '+msg, extra);
      else console.debug('[ChargeChart]['+chartNowIso()+']['+kind+'] '+msg);
    } catch(e){}
  }
  function setChartDebugEnabled(enabled){ chartDebug = !!enabled; try { if (global.localStorage) localStorage.setItem('chartDebug', chartDebug ? '1' : '0'); } catch(_){} }

  // Register chartjs-plugin-zoom if present (even though we use manual pan/zoom)
  (function registerZoomPlugin() {
    try {
      var zp = global['chartjs-plugin-zoom'] || global.ChartZoom || global.ChartZoomPlugin || global.zoomPlugin || global.ChartjsPluginZoom;
      if (zp && global.Chart && global.Chart.register) {
        try { global.Chart.register(zp); } catch (_) {}
      }
    } catch (e) {
      try { console.warn('Failed to register chartjs-plugin-zoom:', e); } catch(_){}
    }
  })();

  var DISPLAYABLE_PRICES_ID = 'charging_prices';
  var DISPLAYABLE_EVENTS_ID = 'charging_events';

  var chart = null;
  var canvas = null;
  var fullXMin = null;
  var fullXMax = null;
  var dtoPrices = null;
  var dtoEvents = null;
  var sseInitDone = false;
  var unsubPrices = null;
  var unsubEvents = null;
  var resizeHandlerInstalled = false;
  var initialFetchDone = false;

  // Determine whether we should perform a one-time initial fetch.
  // This is enabled only for the standalone long-term-charge-chart.html page
  // to support manual local testing environments that may not have SSE.
  // Configuration flag: caller may request a one-time initial fetch (standalone page only)
  var initialFetchRequested = false;

  function toMs(x){ return (x instanceof Date) ? x.getTime() : (typeof x === 'number' ? x : +new Date(x)); }

  // Convert DTO timestamp (seconds with fractional part) to milliseconds.
  // DTO format is fixed: Unix epoch seconds (number or numeric string).
  function dtoTimestampTMs(t){
    if (t instanceof Date) return t.getTime();
    if (typeof t === 'number') return isFinite(t) ? t * 1000 : NaN;
    return NaN;
  }

  function computeFullRange(priceData) {
    var xMin = priceData[0] && priceData[0].x ? toMs(priceData[0].x) : null;
    var xMax = priceData.length > 0 && priceData[priceData.length - 1].x ? toMs(priceData[priceData.length - 1].x) : null;
    return { xMin: xMin, xMax: xMax };
  }
  function isZoomed(c) {
    var s = c && c.scales && c.scales.x;
    if (!s || fullXMin === null || fullXMax === null) return false;
    var curMin = toMs(s.min);
    var curMax = toMs(s.max);
    return (curMin > fullXMin) || (curMax < fullXMax);
  }
  function updateCursor(c) {
    if (!canvas) return;
    canvas.style.cursor = isZoomed(c) ? 'grab' : 'default';
  }

  function buildPriceDatasetsFromDto(pricesDto) {
    var actual = [];
    var predicted = [];
    var all = [];
    var arr = (pricesDto && Array.isArray(pricesDto.prices)) ? pricesDto.prices : [];
    for (var i = 0; i < arr.length; i++) {
      var row = arr[i];
      var t0ms = dtoTimestampTMs(row.t);
      var p0 = Number(row.p);
      var isActual = !!row.a;
      if (!isFinite(p0) || !isFinite(t0ms)) continue;
      var currentArr = isActual ? actual : predicted;
      var pt0 = { x: new Date(t0ms), y: p0 };
      currentArr.push(pt0);
      all.push(pt0);
      if (i < arr.length - 1) {
        var next = arr[i + 1];
        var t1ms = dtoTimestampTMs(next.t);
        if (isFinite(t1ms)) {
          var pt1 = { x: new Date(t1ms), y: p0 };
          currentArr.push(pt1);
          all.push(pt1);
          var p1 = Number(next.p);
          var isActualNext = !!next.a;
          if (isFinite(p1) && isActual !== isActualNext) {
            var ptJoin = { x: new Date(t1ms), y: p1 };
            currentArr.push(ptJoin);
            all.push(ptJoin);
          }
        }
      }
    }
    return { actual: actual, predicted: predicted, all: all };
  }

  function buildEventDatasetsFromDto(eventsDto) {
    var eventDatasets = [];
    var evs = (eventsDto && Array.isArray(eventsDto.events)) ? eventsDto.events : [];
    for (var i = 0; i < evs.length - 1; i += 2) {
      var s = evs[i];
      var e = evs[i + 1];
      var sTms = dtoTimestampTMs(s.t);
      var eTms = dtoTimestampTMs(e.t);
      var sS = Number(s.c);
      var eS = Number(e.c);
      if (!isFinite(sTms) || !isFinite(eTms) || !isFinite(sS) || !isFinite(eS)) continue;
      var isJourney = s.e === 'JS';
      var label = s.s || (isJourney ? 'Journey' : 'Charge');
      eventDatasets.push({
        type: 'line',
        label: label,
        data: [ { x: new Date(sTms), y: sS }, { x: new Date(eTms), y: eS } ],
        borderColor: isJourney ? 'rgba(255,99,132,0.9)' : 'rgba(75,192,192,0.9)',
        backgroundColor: isJourney ? 'rgba(255,99,132,0.35)' : 'rgba(75,192,192,0.35)',
        borderWidth: 3,
        yAxisID: 'y1',
        tension: 0,
        fill: true,
        pointRadius: 0,
        showLine: true
      });
      if (i + 2 < evs.length) {
        var n = evs[i + 2];
        var nTms = dtoTimestampTMs(n.t);
        var nS = Number(n.c);
        if (isFinite(nTms) && isFinite(nS)) {
          eventDatasets.push({
            type: 'line',
            label: 'Idle',
            data: [ { x: new Date(eTms), y: eS }, { x: new Date(nTms), y: nS } ],
            borderColor: 'rgba(150,150,150,0.7)',
            borderDash: [6, 6],
            borderWidth: 1,
            yAxisID: 'y1',
            tension: 0,
            pointRadius: 0,
            showLine: true,
            fill: false,
            plugins: { tooltip: { enabled: false } }
          });
        }
      }
    }
    return eventDatasets;
  }

  function renderFromDtos() {
    try { clog('render-enter', 'canvas='+(!!canvas)+', hasPrices='+(!!dtoPrices)+', hasEvents='+(!!dtoEvents)); } catch(_){}
    // Render as soon as we have any initial data (prices or events) and a canvas.
    if (!canvas) { try { clog('render-skip','no canvas'); } catch(_){ } return; }
    // If neither prices nor events arrived yet, skip until one of them does
    if (!dtoPrices && !dtoEvents) { try { clog('render-skip','no data yet (prices+events both missing)'); } catch(_){ } return; }

    var price = buildPriceDatasetsFromDto(dtoPrices);
    var priceActual = price.actual, pricePredicted = price.predicted, priceAll = price.all;
    var eventDatasets = buildEventDatasetsFromDto(dtoEvents || { events: [] });
    var range = computeFullRange(priceAll);
    var newFullMin = range.xMin, newFullMax = range.xMax;
    try { clog('datasets', 'priceActual='+priceActual.length+', pricePredicted='+pricePredicted.length+', priceAll='+priceAll.length+', eventDatasets='+eventDatasets.length); } catch(_){ }
    // Fallback to event datasets for full range when price data is absent
    if ((newFullMin === null || newFullMax === null) && eventDatasets && eventDatasets.length) {
      try {
        var evMin = null, evMax = null;
        for (var di = 0; di < eventDatasets.length; di++) {
          var ds = eventDatasets[di];
          var data = ds && ds.data ? ds.data : [];
          for (var dj = 0; dj < data.length; dj++) {
            var xms = toMs(data[dj].x);
            if (!isFinite(xms)) continue;
            if (evMin === null || xms < evMin) evMin = xms;
            if (evMax === null || xms > evMax) evMax = xms;
          }
        }
        if (evMin !== null && evMax !== null) { newFullMin = evMin; newFullMax = evMax; }
      } catch(_) {}
    }
    try { clog('range', 'newFullMin='+(newFullMin!==null? new Date(newFullMin).toISOString():'null')+', newFullMax='+(newFullMax!==null? new Date(newFullMax).toISOString():'null')); } catch(_){ }

    // Extend idle (dashed) ranges to chart bounds
    try {
      var evs = (dtoEvents && Array.isArray(dtoEvents.events)) ? dtoEvents.events : [];
      if (priceAll.length > 0 && evs.length >= 2 && newFullMin !== null && newFullMax !== null) {
        var firstStart = evs[0];
        var firstStartTime = new Date(dtoTimestampTMs(firstStart.t));
        var firstStartSoc = Number(firstStart.c);
        var lastEnd = evs[evs.length - 1];
        var lastEndTime = new Date(dtoTimestampTMs(lastEnd.t));
        var lastEndSoc = Number(lastEnd.c);
        if (!isNaN(firstStartTime) && isFinite(firstStartSoc)) {
          eventDatasets.unshift({ type: 'line', label: 'Idle', data: [ { x: new Date(newFullMin), y: firstStartSoc }, { x: firstStartTime, y: firstStartSoc } ], borderColor: 'rgba(150,150,150,0.7)', borderDash: [6,6], borderWidth: 1, yAxisID: 'y1', tension: 0, pointRadius: 0, showLine: true, fill: false, plugins: { tooltip: { enabled: false } } });
        }
        if (!isNaN(lastEndTime) && isFinite(lastEndSoc)) {
          eventDatasets.push({ type: 'line', label: 'Idle', data: [ { x: lastEndTime, y: lastEndSoc }, { x: new Date(newFullMax), y: lastEndSoc } ], borderColor: 'rgba(150,150,150,0.7)', borderDash: [6,6], borderWidth: 1, yAxisID: 'y1', tension: 0, pointRadius: 0, showLine: true, fill: false, plugins: { tooltip: { enabled: false } } });
        }
      }
    } catch (e) { try { console.warn('Failed to extend idle dashed range:', e); } catch(_){} }

    var PRICE_COLOR_ACTUAL = 'blue';
    var PRICE_FILL_ACTUAL = 'rgba(0,0,255,0.10)';
    var PRICE_COLOR_PRED = 'rgba(0,0,255,0.5)';
    var PRICE_FILL_PRED = 'rgba(0,0,255,0.05)';

    var priceDatasets = [];
    if (priceActual.length) {
      priceDatasets.push({ label: 'Electricity Price (p/kWh)', data: priceActual, borderColor: PRICE_COLOR_ACTUAL, backgroundColor: PRICE_FILL_ACTUAL, yAxisID: 'y', stepped: true, tension: 0, fill: true, pointRadius: 0, pointHoverRadius: 0 });
    }
    if (pricePredicted.length) {
      priceDatasets.push({ label: 'Electricity Price (p/kWh)', data: pricePredicted, borderColor: PRICE_COLOR_PRED, backgroundColor: PRICE_FILL_PRED, yAxisID: 'y', stepped: true, tension: 0, fill: true, pointRadius: 0, pointHoverRadius: 0 });
    }

    if (!chart) {
      try {
        var rect = canvas && canvas.getBoundingClientRect ? canvas.getBoundingClientRect() : null;
        var cw = rect ? Math.round(rect.width) : null;
        var ch = rect ? Math.round(rect.height) : null;
        clog('chart-create', 'creating chart; priceDs='+priceDatasets.length+', eventDs='+eventDatasets.length+', canvas='+cw+'x'+ch);
      } catch(_){}
      var ctx = canvas.getContext('2d');
      chart = new global.Chart(ctx, {
        type: 'line',
        data: { datasets: priceDatasets.concat(eventDatasets) },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          layout: { padding: 0 },
          animation: false,
          interaction: { mode: 'nearest', intersect: false },
          plugins: {
            legend: { display: false },
            tooltip: { displayColors: false, filter: function(ctx){ try { return !(ctx && ctx.dataset && ctx.dataset.label === 'Idle'); } catch(_) { return true; } } },
            // keep plugin disabled for zoom; manual handlers below
            zoom: {
              pan: { enabled: false },
              zoom: { wheel: { enabled: false }, pinch: { enabled: false } }
            }
          },
          scales: {
            x: {
              type: 'time',
              time: { unit: 'minute', tooltipFormat: 'EEE dd MMM yyyy HH:mm', displayFormats: { minute: 'EEE dd MMM HH:mm', hour: 'EEE dd MMM HH:mm', day: 'EEE dd MMM HH:mm' } },
              title: { display: true, text: 'Timestamp' }
            },
            y: { position: 'left', title: { display: true, text: 'Price (p/kWh)' }, grid: { drawOnChartArea: false } },
            y1: { position: 'right', min: 0, max: 100, title: { display: true, text: 'State of Charge (%)' }, grid: { drawOnChartArea: true } }
          }
        }
      });

      // ---- Manual pan/zoom logic (mouse + touch) ----
      // Mouse pan when zoomed
      var dragging = false; var lastX = 0;
      function currentlyZoomed(){ return chart && isZoomed(chart); }
      canvas.addEventListener('mousedown', function(e){
        if (!currentlyZoomed()) return;
        dragging = true; lastX = e.clientX; canvas.style.cursor = 'grabbing'; e.preventDefault();
      });
      global.addEventListener('mousemove', function(e){
        if (!dragging) return;
        var dx = e.clientX - lastX; lastX = e.clientX;
        var xScale = chart.scales.x; if (!xScale) return;
        var center = (xScale.left + xScale.right) / 2;
        var v0 = xScale.getValueForPixel(center);
        var v1 = xScale.getValueForPixel(center + dx);
        var deltaVal = v1 - v0;
        var curMin = toMs(xScale.min); var curMax = toMs(xScale.max);
        if (!(curMin < curMax)) { curMin = (fullXMin !== null ? fullXMin : curMin); curMax = (fullXMax !== null ? fullXMax : curMax); }
        var newMin = curMin - deltaVal; var newMax = curMax - deltaVal;
        if (fullXMin !== null && newMin < fullXMin) { var off1 = fullXMin - newMin; newMin += off1; newMax += off1; }
        if (fullXMax !== null && newMax > fullXMax) { var off2 = newMax - fullXMax; newMin -= off2; newMax -= off2; }
        chart.options.scales.x.min = newMin; chart.options.scales.x.max = newMax; chart.update('none');
      });
      global.addEventListener('mouseup', function(){ if (!dragging) return; dragging = false; updateCursor(chart); });

      // Wheel zoom
      canvas.addEventListener('wheel', function(e){
        try {
          e.preventDefault();
          var xScale = chart.scales.x; if (!xScale) return;
          var rect = canvas.getBoundingClientRect();
          var px = e.clientX - rect.left; if (!isFinite(px)) return;
          var centerVal = xScale.getValueForPixel(px); if (!isFinite(centerVal)) return;
          var curMin = toMs(xScale.min); var curMax = toMs(xScale.max);
          if (!(curMin < curMax)) { curMin = (fullXMin !== null ? fullXMin : curMin); curMax = (fullXMax !== null ? fullXMax : curMax); }
          var range = curMax - curMin; if (!(range > 0)) return;
          var step = 0.0015; var scale = Math.exp(-e.deltaY * step); if (!isFinite(scale) || scale === 0) return;
          var newRange = range / scale; var t = (centerVal - curMin) / range; var newMin = centerVal - t * newRange; var newMax = newMin + newRange;
          if (fullXMin !== null) { var over1 = fullXMin - newMin; if (over1 > 0) { newMin += over1; newMax += over1; } }
          if (fullXMax !== null) { var over2 = newMax - fullXMax; if (over2 > 0) { newMin -= over2; newMax -= over2; } }
          var minSpan = 60 * 1000; if (newMax - newMin < minSpan) { var c = (newMax + newMin) / 2; newMin = c - minSpan / 2; newMax = c + minSpan / 2; }
          chart.options.scales.x.min = newMin; chart.options.scales.x.max = newMax; chart.update('none'); updateCursor(chart);
        } catch (_) {}
      }, { passive: false });

      // Touch pan + pinch zoom
      var pinchActive = false; var lastPinchDist = 0; var lastPinchCenter = {x:0,y:0};
      var draggingTouch = false; var lastXTouch = 0; var lastPinchEndTime = 0;

      function pinchDistance(t1, t2){ var dx = t1.clientX - t2.clientX; var dy = t1.clientY - t2.clientY; return Math.hypot(dx, dy); }
      function pinchMidpoint(t1, t2){ return { x: (t1.clientX + t2.clientX) / 2, y: (t1.clientY + t2.clientY) / 2 }; }

      canvas.addEventListener('touchstart', function(e){
        if (e.touches.length === 1 && isZoomed(chart)) {
          draggingTouch = true; lastXTouch = e.touches[0].clientX; e.preventDefault();
        } else if (e.touches.length === 2) {
          draggingTouch = false; pinchActive = true; lastPinchDist = pinchDistance(e.touches[0], e.touches[1]); lastPinchCenter = pinchMidpoint(e.touches[0], e.touches[1]); e.preventDefault();
        }
      }, { passive: false, capture: true });

      canvas.addEventListener('touchmove', function(e){
        try {
          if (pinchActive && e.touches.length === 2) {
            var t1 = e.touches[0], t2 = e.touches[1];
            var dist = pinchDistance(t1, t2);
            var center = pinchMidpoint(t1, t2);
            var xScale = chart.scales.x; if (!xScale) return;
            var rect = canvas.getBoundingClientRect();
            var px = center.x - rect.left; if (!isFinite(px)) return;
            var centerVal = xScale.getValueForPixel(px); if (!isFinite(centerVal)) return;
            var curMin = toMs(xScale.min); var curMax = toMs(xScale.max);
            if (!(curMin < curMax)) { curMin = (fullXMin !== null ? fullXMin : curMin); curMax = (fullXMax !== null ? fullXMax : curMax); }
            var range = curMax - curMin; if (!(range > 0)) return;
            var scale = (lastPinchDist > 0 ? (dist / lastPinchDist) : 1); if (!isFinite(scale) || scale === 0) return;
            var newRange = range / scale; var t = (centerVal - curMin) / range; var newMin = centerVal - t * newRange; var newMax = newMin + newRange;
            if (fullXMin !== null) { var over1 = fullXMin - newMin; if (over1 > 0) { newMin += over1; newMax += over1; } }
            if (fullXMax !== null) { var over2 = newMax - fullXMax; if (over2 > 0) { newMin -= over2; newMax -= over2; } }
            var minSpan = 60 * 1000; if (newMax - newMin < minSpan) { var c = (newMax + newMin) / 2; newMin = c - minSpan / 2; newMax = c + minSpan / 2; }
            chart.options.scales.x.min = newMin; chart.options.scales.x.max = newMax; chart.update('none'); updateCursor(chart);
            lastPinchDist = dist; lastPinchCenter = center; e.preventDefault();
          } else if (draggingTouch && e.touches.length === 1) {
            var x = e.touches[0].clientX; var dx = x - lastXTouch; lastXTouch = x;
            var xScale2 = chart.scales.x; if (!xScale2) return;
            var center2 = (xScale2.left + xScale2.right) / 2; var v0b = xScale2.getValueForPixel(center2); var v1b = xScale2.getValueForPixel(center2 + dx); var deltaVal = v1b - v0b;
            var curMin2 = toMs(xScale2.min); var curMax2 = toMs(xScale2.max);
            if (!(curMin2 < curMax2)) { curMin2 = (fullXMin !== null ? fullXMin : curMin2); curMax2 = (fullXMax !== null ? fullXMax : curMax2); }
            var newMin2 = curMin2 - deltaVal; var newMax2 = curMax2 - deltaVal;
            if (fullXMin !== null && newMin2 < fullXMin) { var off1b = fullXMin - newMin2; newMin2 += off1b; newMax2 += off1b; }
            if (fullXMax !== null && newMax2 > fullXMax) { var off2b = newMax2 - fullXMax; newMin2 -= off2b; newMax2 -= off2b; }
            chart.options.scales.x.min = newMin2; chart.options.scales.x.max = newMax2; chart.update('none'); e.preventDefault();
          }
        } catch (_) {}
      }, { passive: false });

      canvas.addEventListener('touchend', function(e){
        if (pinchActive && e.touches.length < 2) { pinchActive = false; lastPinchDist = 0; lastPinchEndTime = Date.now(); }
        if (draggingTouch) { draggingTouch = false; updateCursor(chart); }
      }, { passive: false });

      // Double-click / double-tap reset to full
      function resetToFull(){
        try { if (chart && chart.resetZoom) chart.resetZoom(); } catch(_){}
        chart.options.scales.x.min = undefined; chart.options.scales.x.max = undefined; chart.update('none'); updateCursor(chart);
      }
      canvas.addEventListener('dblclick', function(e){ e.preventDefault(); resetToFull(); });
      var lastTapTime = 0;
      canvas.addEventListener('touchend', function(e){
        var now = Date.now();
        if ((e.changedTouches && e.changedTouches.length) >= 2) return;
        if (now - lastPinchEndTime < 400) { lastTapTime = 0; return; }
        if (e.touches && e.touches.length === 0) {
          if (now - lastTapTime < 300) { resetToFull(); lastTapTime = 0; try { e.preventDefault(); e.stopPropagation(); } catch(_){} return; }
          lastTapTime = now;
        }
      }, { passive: false });

      // Tooltip dismiss (click/tap away from closest point)
      (function addTooltipDismissHandlers(){
        var HIDE_DISTANCE_PX = 40;
        function clearTooltip(){
          try { chart.setActiveElements && chart.setActiveElements([]); } catch(_){}
          try { chart.tooltip && chart.tooltip.setActiveElements([], {x:0,y:0}); } catch(_){}
          chart.update('none');
        }
        function shouldClearAtClientXY(clientX, clientY){
          if (!chart) return false;
          var rect = canvas.getBoundingClientRect();
          var x = clientX - rect.left; var y = clientY - rect.top;
          var ca = chart.chartArea; if (!ca || x < ca.left || x > ca.right || y < ca.top || y > ca.bottom) return true;
          var items = [];
          try {
            var fakeEvt = { type: 'click', clientX: clientX, clientY: clientY, target: canvas };
            items = chart.getElementsAtEventForMode(fakeEvt, 'nearest', { intersect: false }, true) || [];
          } catch(_){}
          var el = items[0] && items[0].element;
          if (!el || typeof el.x !== 'number' || typeof el.y !== 'number') return true;
          var dx = x - el.x; var dy = y - el.y; var dist = Math.hypot(dx, dy);
          return dist > HIDE_DISTANCE_PX;
        }
        canvas.addEventListener('click', function(e){ setTimeout(function(){ try { if (shouldClearAtClientXY(e.clientX, e.clientY)) clearTooltip(); } catch(_){} }, 0); }, { passive: true });
        canvas.addEventListener('touchend', function(e){ if (e.defaultPrevented) return; if (!e.changedTouches || e.changedTouches.length !== 1) return; var t = e.changedTouches[0]; setTimeout(function(){ try { if (shouldClearAtClientXY(t.clientX, t.clientY)) clearTooltip(); } catch(_){} }, 0); }, { passive: true });
      })();

      // Initialize full range
      fullXMin = newFullMin; fullXMax = newFullMax; updateCursor(chart);
      try { clog('chart-created', 'full=['+(fullXMin!==null? new Date(fullXMin).toISOString():'null')+','+(fullXMax!==null? new Date(fullXMax).toISOString():'null')+']'); } catch(_){ }
    }

    // Update datasets and preserve zoom if reasonable
    var wasZoomed = isZoomed(chart);
    var xScaleNow = chart.scales.x || {};
    var prevMin = toMs(xScaleNow.min);
    var prevMax = toMs(xScaleNow.max);
    try { clog('chart-update','updating datasets; priceDs='+priceDatasets.length+', eventDs='+eventDatasets.length+', wasZoomed='+wasZoomed+ (isFinite(prevMin)&&isFinite(prevMax) ? (', prev=['+new Date(prevMin).toISOString()+','+new Date(prevMax).toISOString()+']') : '')); } catch(_){ }

    chart.data.datasets = priceDatasets.concat(eventDatasets);
    fullXMin = newFullMin; fullXMax = newFullMax;
    if (wasZoomed && fullXMin !== null && fullXMax !== null) {
      var clampedMin = Math.max(prevMin, fullXMin);
      var clampedMax = Math.min(prevMax, fullXMax);
      if (clampedMax > clampedMin) {
        chart.options.scales.x.min = clampedMin;
        chart.options.scales.x.max = clampedMax;
      } else {
        chart.options.scales.x.min = undefined;
        chart.options.scales.x.max = undefined;
      }
    } else {
      chart.options.scales.x.min = undefined;
      chart.options.scales.x.max = undefined;
    }
    chart.update('none');
    updateCursor(chart);
    try {
      var xs2 = chart.scales && chart.scales.x;
      var cmin = xs2 ? toMs(xs2.min) : null;
      var cmax = xs2 ? toMs(xs2.max) : null;
      clog('chart-after-update', 'x=['+(isFinite(cmin)? new Date(cmin).toISOString():'?')+','+(isFinite(cmax)? new Date(cmax).toISOString():'?')+'], full=['+(fullXMin!==null? new Date(fullXMin).toISOString():'null')+','+(fullXMax!==null? new Date(fullXMax).toISOString():'null')+']');
    } catch(_){ }
  }

  function initSseAndInitialLoad(){
    try { clog('init-sse-start', 'sseInitDone='+sseInitDone+', hasDisplayableSse='+(!!global.DisplayableSse)); } catch(_){ }
    if (sseInitDone) { try { clog('init-sse-skip','already initialized'); } catch(_){ } return; }
    sseInitDone = true;
    try { if (global.DisplayableSse) { global.DisplayableSse.ensureConnected(); clog('ensureConnected-called','OK'); } } catch(_){ try { clog('ensureConnected-called','threw'); } catch(__){} }
    // Standalone page exception: fetch once on page load to support environments without SSE.
    // Main web application (index.html) must not fetch; rely solely on SSE/cached DTO replay.
    if (initialFetchRequested && !initialFetchDone) {
      initialFetchDone = true;
      try {
        if (global.DisplayableSse && global.DisplayableSse.fetchItem) {
          global.DisplayableSse.fetchItem(DISPLAYABLE_PRICES_ID)
            .then(function(dto){ dtoPrices = dto || { type: 'charging_prices', prices: [] }; renderFromDtos(); })
            .catch(function(e){ try { console.warn('Initial price fetch failed', e); } catch(_){} });
          global.DisplayableSse.fetchItem(DISPLAYABLE_EVENTS_ID)
            .then(function(dto){ dtoEvents = dto || { type: 'charging_events', events: [] }; renderFromDtos(); })
            .catch(function(e){ try { console.warn('Initial events fetch failed', e); } catch(_){} });
        }
      } catch(_){}
    }
    try {
      unsubPrices = global.DisplayableSse && global.DisplayableSse.subscribe(DISPLAYABLE_PRICES_ID, function(dto){ dtoPrices = dto; try { clog('sse-update','prices dto received'); } catch(_){ } renderFromDtos(); });
      if (unsubPrices) { try { clog('subscribe','prices subscription installed'); } catch(_){ } }
    } catch(_){}
    try {
      unsubEvents = global.DisplayableSse && global.DisplayableSse.subscribe(DISPLAYABLE_EVENTS_ID, function(dto){ dtoEvents = dto; try { clog('sse-update','events dto received'); } catch(_){ } renderFromDtos(); });
      if (unsubEvents) { try { clog('subscribe','events subscription installed'); } catch(_){ } }
    } catch(_){}
  }

  function start(options){
    try { clog('start','called'); } catch(_){ }
    try { initialFetchRequested = !!(options && options.initialFetch); } catch(_){ }
    initSseAndInitialLoad();
  }
  function stop(){
    try { clog('stop','called'); } catch(_){ }
    try { if (typeof unsubPrices === 'function') { unsubPrices(); clog('unsubscribe','prices'); } } catch(_){ }
    try { if (typeof unsubEvents === 'function') { unsubEvents(); clog('unsubscribe','events'); } } catch(_){ }
    unsubPrices = null; unsubEvents = null; sseInitDone = false;
  }

  function init(canvasOrId){
    try { clog('init','called with '+ (typeof canvasOrId)); } catch(_){ }
    if (canvas) { try { clog('init-skip','already init'); } catch(_){ } return; }
    canvas = (typeof canvasOrId === 'string') ? global.document.getElementById(canvasOrId) : canvasOrId;
    if (!canvas) { try { console.warn('ChargeChart.init: canvas not found'); clog('init-fail','canvas not found'); } catch(_){} return; }
    try {
      var rect = canvas.getBoundingClientRect();
      clog('init-canvas','size='+Math.round(rect.width)+'x'+Math.round(rect.height));
    } catch(_){ }
    if (!resizeHandlerInstalled) {
      resizeHandlerInstalled = true;
      global.addEventListener('resize', function(){ try { if (chart) chart.resize(); } catch(_){} });
      global.addEventListener('visibilitychange', function(){ if (!global.document.hidden) { try { global.DisplayableSse && global.DisplayableSse.ensureConnected(); } catch(_){} try { renderFromDtos(); } catch(_){} } });
      global.addEventListener('pagehide', stop);
      global.addEventListener('beforeunload', stop);
      try { clog('init','lifecycle handlers installed'); } catch(_){ }
    }
    try { clog('init','render attempt after init'); } catch(_){ }
    try { renderFromDtos(); } catch(_){ }
  }

  global.ChargeChart = { init: init, start: start, stop: stop, setDebug: setChartDebugEnabled };

})(typeof window !== 'undefined' ? window : this);
