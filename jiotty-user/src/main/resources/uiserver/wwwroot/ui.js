$(function () {
  const MAIN_TAB_TARGETS = {
    state: '#state-pane',
    options: '#options-pane',
    charge: '#charging-pane'
  };

  // ----- Responsive navigation -----
  let bottomNavActive = false;

  function isMobileViewport() {
    return window.matchMedia('(max-width: 767.98px)').matches;
  }

  function applyResponsiveNav() {
    const mobile = isMobileViewport();
    if (mobile) {
      $('.desktop-tabs').addClass('d-none');
      $('.bottom-nav').removeClass('d-none');
      document.body.classList.add('has-bottom-nav');
    } else {
      $('.bottom-nav').addClass('d-none');
      $('.desktop-tabs').removeClass('d-none');
      document.body.classList.remove('has-bottom-nav');
    }
    return mobile;
  }

  bottomNavActive = applyResponsiveNav();
  window.addEventListener('resize', function () {
    const prev = bottomNavActive;
    bottomNavActive = applyResponsiveNav();
    setupMobileSubtabsFor('#stateTab');
    setupMobileSubtabsFor('#optionsTab');
    if (bottomNavActive !== prev) bindNavigationHandlers();
  });

  // ----- URL hash with sub-tabs -----
  function parseHash() {
    const h = window.location.hash || '';
    const m = h.match(/^#(state|options|charge|charging|charging-pane)(?:\/(.*))?$/i);
    if (!m) return {main: null, sub: null};
    let main = m[1].toLowerCase();
    if (main === 'charging-pane' || main === 'charging') main = 'charge';
    const sub = m[2] ? decodeURIComponent(m[2]) : null;
    return {main, sub};
  }

  function updateHash(obj) {
    let h = '';
    if (obj.main === 'state') {
      h = '#state' + (obj.sub ? '/' + encodeURIComponent(obj.sub) : '');
    } else if (obj.main === 'options') {
      h = '#options' + (obj.sub ? '/' + encodeURIComponent(obj.sub) : '');
    } else if (obj.main === 'charge') {
      h = '#charge';
    }
    history.replaceState(null, '', window.location.pathname + window.location.search + (h || ''));
  }

  function showMainTab(main) {
    const target = MAIN_TAB_TARGETS[main];
    if (!target) return false;
    const el = document.querySelector('[data-bs-target="' + target + '"]');
    if (el) {
      new bootstrap.Tab(el).show();
      return true;
    }
    return false;
  }

  function selectStateSubTabByOriginalId(originalId) {
    const esc = CSS.escape(originalId);
    const body = document.querySelector('#state-pane .displayable-body[data-original-id="' + esc + '"]');
    if (!body) return false;
    const pane = body.closest('.tab-pane');
    if (!pane) return false;
    const el = document.querySelector('#stateTab [data-bs-target="#' + pane.id + '"]');
    if (el) {
      new bootstrap.Tab(el).show();
      return true;
    }
    return false;
  }

  function selectOptionsSubTabById(tabId) {
    const el = document.querySelector('#optionsTab [data-bs-target="#' + tabId + '"]');
    if (el) {
      new bootstrap.Tab(el).show();
      return true;
    }
    return false;
  }

  function activateFirstOptionsTab() {
    const el = document.querySelector('#optionsTab button[data-bs-toggle="tab"]');
    if (el) {
      new bootstrap.Tab(el).show();
      return true;
    }
    return false;
  }

  function activeOptionsTabId() {
    const el = document.querySelector('#options-pane .tab-pane.active');
    return el ? el.id : null;
  }

  function activeStateOriginalId() {
    const el = document.querySelector('#stateTabContent .tab-pane.active .displayable-body');
    return el ? el.getAttribute('data-original-id') : null;
  }

  // ----- Charging chart control -----
  function sendChargeControl(action) {
    if (!window.ChargeChart) return;
        if (action === 'start') {
          if (!document.getElementById('combinedChart')) return;
          window.ChargeChart.init('combinedChart');
          if (window.ChargeChart.start) window.ChargeChart.start();
        } else if (action === 'stop') {
          if (window.ChargeChart.stop) window.ChargeChart.stop();
        }
  }

  // ----- State SSE subscriptions -----
  let stateUnsubById = Object.create(null);

  function unsubscribeAllState() {
    Object.keys(stateUnsubById).forEach(function (id) {
      if (stateUnsubById[id]) stateUnsubById[id]();
    });
    stateUnsubById = Object.create(null);
  }

  function subscribeAllState() {
    if (typeof DisplayableSse === 'undefined' || !DisplayableSse.subscribe) return;
    document.querySelectorAll('#stateTabContent .displayable-body[data-original-id]').forEach(function (el) {
      const id = el.getAttribute('data-original-id');
      if (!id || stateUnsubById[id]) return;
      stateUnsubById[id] = DisplayableSse.subscribe(id, function (dto) {
        if (!el || !document.body.contains(el)) return;
        const table = el.querySelector('table');
        const tableScrollLeft = table ? (table.scrollLeft || 0) : 0;
        const prevTop = el.scrollTop;
        const prevLeft = el.scrollLeft;
        el.innerHTML = renderDisplayable(dto);
        el.scrollTop = prevTop;
        el.scrollLeft = prevLeft;
        requestAnimationFrame(function () {
          const newTable = el.querySelector('table');
          if (newTable) newTable.scrollLeft = tableScrollLeft;
        });
      });
    });
  }

  function ensureStateStreaming() {
    if (typeof DisplayableSse !== 'undefined') DisplayableSse.ensureConnected();
    subscribeAllState();
  }

  // ----- Rendering -----
  function escapeHtml(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c];
    });
  }

  function renderDisplayable(dto) {
    if (!dto || dto.type !== 'history') return '';
    let html = '<table class="pure-table"><tr><th>What</th><th>Stats</th></tr>';
    const groups = dto.groups || {};
    Object.keys(groups).forEach(function (what) {
      const entries = groups[what] || [];
      const inner = entries.map(function (en) {
        const textFmt = (en && en.format) ? String(en.format).toLowerCase() : 'plain';
        const textCell = textFmt === 'html' ? (en.text || '') : escapeHtml(en.text || '');
        return '<tr><td>' + escapeHtml(en.time || '') + '</td><td>' + textCell + '</td></tr>';
      }).join('');
      html += '<tr><td style="vertical-align: top">' + escapeHtml(what || '') + '</td><td><table class="pure-table">' + inner + '</table></td></tr>';
    });
    html += '</table>';
    return html;
    }

  // ----- Tab building -----
  function buildStateTabs(items) {
    const stateTab = $('#stateTab');
    const content = $('#stateTabContent');
    unsubscribeAllState();
    stateTab.empty();
    content.empty();
    let first = true;
    items.forEach(function (it) {
      const active = first ? ' active' : '';
      const show = first ? ' show active' : '';
      stateTab.append(
          '<li class="nav-item">' +
          '<button class="nav-link' + active + '" id="state-' + it.safeId + '-tab" data-bs-toggle="tab" data-bs-target="#state-' + it.safeId + '-pane" type="button" role="tab" aria-controls="state-' + it.safeId + '-pane" aria-selected="' + first + '">' + escapeHtml(it.name) + '</button>' +
          '</li>'
      );
      content.append(
          '<div class="tab-pane fade' + show + '" id="state-' + it.safeId + '-pane" role="tabpanel" aria-labelledby="state-' + it.safeId + '-tab">' +
          '<div class="displayable-body border rounded p-2" id="dispbody-' + it.safeId + '" data-original-id="' + it.id + '">Loading\u2026</div>' +
          '</div>'
      );
      first = false;
    });
  }

  function optionToHtml(opt) {
    const key = escapeHtml(opt.key);
    const label = escapeHtml(opt.label || opt.key);
    const type = opt.type;
    const statusSpan = '<span class="save-status" id="status-' + key + '">Saved</span>';
    if (type === 'text') {
      const val = opt.value != null ? ' value="' + escapeHtml(opt.value) + '"' : '';
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<input id="' + key + '" type="text" class="form-control d-inline-block w-auto" data-option="' + key + '" data-option-type="text"' + val + '>' +
          statusSpan +
          '</div>';
    } else if (type === 'textarea') {
      const rows = opt.rows || 3;
      const tval = opt.value != null ? escapeHtml(opt.value) : '';
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<textarea id="' + key + '" class="form-control w-100" rows="' + rows + '" data-option="' + key + '" data-option-type="textarea">' + tval + '</textarea>' +
          statusSpan +
          '</div>';
    } else if (type === 'checkbox') {
      const checked = opt.checked ? ' checked' : '';
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<input id="' + key + '" type="checkbox" data-option="' + key + '" data-option-type="checkbox"' + checked + '>' +
          statusSpan +
          '</div>';
    } else if (type === 'time') {
      const tval = opt.value != null ? ' value="' + escapeHtml(opt.value) + '"' : '';
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<input id="' + key + '" type="time" class="form-control d-inline-block w-auto" data-option="' + key + '" data-option-type="time"' + tval + '>' +
          statusSpan +
          '</div>';
    } else if (type === 'duration') {
      const dval = opt.valueHuman != null ? ' value="' + escapeHtml(opt.valueHuman) + '"' : '';
      const placeholder = opt.placeholder ? ' placeholder="' + escapeHtml(opt.placeholder) + '"' : '';
      const title = opt.help ? ' title="' + escapeHtml(opt.help) + '"' : '';
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<input id="' + key + '" type="text" class="form-control d-inline-block w-auto" data-option="' + key + '" data-option-type="text"' + placeholder + title + dval + '>' +
          statusSpan +
          '</div>';
    } else if (type === 'select') {
      const opts = (opt.options || []).map(function (o) {
        const sel = (opt.value === o) ? ' selected' : '';
        return '<option value="' + escapeHtml(o) + '"' + sel + '>' + escapeHtml(o) + '</option>';
      }).join('');
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<select id="' + key + '" class="form-control d-inline-block w-auto" data-option="' + key + '" data-option-type="select">' + opts + '</select>' +
          statusSpan +
          '</div>';
    } else if (type === 'multiselect') {
      const entries = opt.allOptions || {};
      const selected = new Set(opt.selectedIds || []);
      const size = Math.min(10, Object.keys(entries).length || 3);
      const mopts = Object.entries(entries).map(function (e) {
        const id = e[0], name = e[1];
        const sel = selected.has(id) ? ' selected' : '';
        return '<option value="' + escapeHtml(id) + '"' + sel + '>' + escapeHtml(name) + '</option>';
      }).join('');
      return '<div class="form-group mb-3">' +
          '<label for="' + key + '">' + label + '</label>' +
          '<select id="' + key + '" class="form-control d-inline-block w-auto" multiple size="' + size + '" data-option="' + key + '" data-option-type="multiselect">' + mopts + '</select>' +
          statusSpan +
          '</div>';
    } else if (type === 'chat') {
      const history = opt.historyText ? escapeHtml(opt.historyText) : '';
      return '<fieldset><legend>' + label + '</legend>' +
          '<div class="form-group chat-block" data-option="' + key + '" data-option-type="chat">' +
          '<textarea id="history-' + key + '" class="form-control mb-2" rows="8" readonly data-history-for="' + key + '">' + history + '</textarea>' +
          '<div class="input-group mb-2">' +
          '<input id="input-' + key + '" type="text" class="form-control" data-input-for="' + key + '" placeholder="Enter Command\u2026">' +
          '<button type="button" class="btn btn-primary" data-send-for="' + key + '">Send</button>' +
          '</div>' +
          '</div>' +
          '</fieldset>';
    }
    return '';
  }

  function buildOptionsTabs(tabs) {
    const optionsTab = $('#optionsTab');
    const content = $('#optionsTabContent');
    optionsTab.empty();
    content.empty();
    let first = true;
    tabs.forEach(function (tab) {
      const active = first ? ' active' : '';
      const show = first ? ' show active' : '';
      optionsTab.append(
          '<li class="nav-item">' +
          '<button class="nav-link' + active + '" id="' + tab.id + '-tab" data-bs-toggle="tab" data-bs-target="#' + tab.id + '" type="button" role="tab" aria-controls="' + tab.id + '" aria-selected="' + first + '">' + escapeHtml(tab.name) + '</button>' +
          '</li>'
      );
      const inner = (tab.options || []).map(optionToHtml).join('');
      content.append(
          '<div class="tab-pane fade' + show + '" id="' + tab.id + '" role="tabpanel" aria-labelledby="' + tab.id + '-tab">' + inner + '</div>'
      );
      first = false;
    });
  }

  // ----- Option save handlers -----
  function attachOptionHandlers() {
    $('[data-option][data-option-type!="chat"]').each(function () {
      const $el = $(this);
      const name = $el.data('option');
      const type = $el.data('option-type');
      const $stat = $el.closest('.form-group').find('.save-status');

      // per-field hide timer to avoid race conditions between rapid saves
      function clearHideTimer() {
        const t = $stat.data('hideTimer');
        if (t) {
          clearTimeout(t);
          $stat.removeData('hideTimer');
        }
      }

      function getCurrentValue() {
        if (type === 'checkbox') return $el.is(':checked');
        if (type === 'multiselect') return ($el.val() || []).join(',');
        return $el.val();
      }

      // Track last saved value to avoid unnecessary POSTs
      let lastVal = getCurrentValue();
      // Track latest request sequence to ignore stale responses
      let reqSeq = 0;

      if (type === 'text' || type === 'textarea') {
        $el.on('blur', maybePost);
        if (type === 'text') {
          $el.on('keydown', function (e) {
            if (e.key === 'Enter') {
              e.preventDefault();
              maybePost();
            }
          });
        }
      } else {
        $el.on('change', maybePost);
      }

      function maybePost() {
        const curr = getCurrentValue();
        if (curr === lastVal) {
          if ($stat.hasClass('error')) $stat.removeClass('error show');
          return;
        }
        $stat.removeClass('error show');
        const mySeq = ++reqSeq;
        clearHideTimer();
        $.post('options', {name, value: curr})
            .done(function (resp) {
              if (mySeq !== reqSeq) return;
              if (type === 'checkbox') {
                const boolVal = (resp === 'true' || resp === true);
                $el.prop('checked', boolVal);
                lastVal = boolVal;
              } else if (type === 'multiselect') {
                const arr = resp ? resp.split(',') : [];
                $el.val(arr);
                lastVal = arr.join(',');
              } else {
                $el.val(resp);
                lastVal = resp;
              }
              clearHideTimer();
              $stat.text('Saved').removeClass('error').addClass('show');
              const t = setTimeout(function () {
                $stat.text('Saved').removeClass('show');
                $stat.removeData('hideTimer');
              }, 1000);
              $stat.data('hideTimer', t);
            })
            .fail(function (jqXHR) {
              if (mySeq !== reqSeq) return;
              clearHideTimer();
              $stat.text(jqXHR.responseText || 'Error').addClass('error show');
            });
      }
    });

    // Chat widgets
    $('[data-option-type="chat"]').each(function () {
      const c = $(this);
      const name = c.data('option');
      const $input = c.find('[data-input-for="' + name + '"]');
      const $send = c.find('[data-send-for="' + name + '"]');
      const $hist = c.find('[data-history-for="' + name + '"]');

      function sendMsg() {
        const msg = $input.val().trim();
        if (!msg) return;
        $.post('options', {name, value: msg})
            .done(function (responseBody) {
              const decoded = $('<div/>').html(responseBody).text();
              $hist.val(decoded);
              $hist[0].scrollTop = $hist[0].scrollHeight;
              $input.val('').focus();
            })
            .fail(function () {
              alert('Send failed');
            });
      }

      $send.on('click', function (e) {
        e.preventDefault();
        sendMsg();
      });
      $input.on('keydown', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          sendMsg();
        }
      });
      $hist.each(function () {
        this.scrollTop = this.scrollHeight;
      });
    });
  }

  // ----- Navigation handlers -----
  function bindNavigationHandlers() {
    $('.nav-switch').off('click');
    $('#mainTab button[data-bs-toggle="tab"]').off('shown.bs.tab');
    $('#stateTab button[data-bs-toggle="tab"]').off('shown.bs.tab');
    $('#optionsTab button[data-bs-toggle="tab"]').off('shown.bs.tab');

    if (bottomNavActive) {
      $('.nav-switch').on('click', function () {
        const target = $(this).data('target');
        const el = document.querySelector('[data-bs-target="' + target + '"]');
        if (el) new bootstrap.Tab(el).show();
      });
    }

    $('#mainTab button[data-bs-toggle="tab"]').on('shown.bs.tab', function (e) {
      const target = $(e.target).data('bsTarget');
      if (bottomNavActive) {
        $('.nav-switch').removeClass('active').filter('[data-target="' + target + '"]').addClass('active');
      }
      if (target === '#state-pane') {
        updateHash({main: 'state', sub: activeStateOriginalId()});
        ensureStateStreaming();
        sendChargeControl('stop');
        setupMobileSubtabsFor('#stateTab');
      } else if (target === '#options-pane') {
        const h = parseHash();
        if (!document.querySelector('#options-pane .tab-pane.active')) activateFirstOptionsTab();
        if (!(h && h.main === 'options' && h.sub)) updateHash({main: 'options', sub: activeOptionsTabId()});
        setupMobileSubtabsFor('#optionsTab');
        sendChargeControl('stop');
      } else if (target === '#charging-pane') {
        updateHash({main: 'charge'});
        sendChargeControl('start');
      }
    });

    $('#stateTab button[data-bs-toggle="tab"]').on('shown.bs.tab', function () {
      updateHash({main: 'state', sub: activeStateOriginalId()});
      ensureStateStreaming();
      setupMobileSubtabsFor('#stateTab');
    });

    $('#optionsTab button[data-bs-toggle="tab"]').on('shown.bs.tab', function () {
      updateHash({main: 'options', sub: activeOptionsTabId()});
      setupMobileSubtabsFor('#optionsTab');
    });
  }

  function activateFromHash() {
    const h = parseHash();
    if (!h.main) return;
    if (h.main === 'options') {
      if (h.sub) selectOptionsSubTabById(h.sub); else activateFirstOptionsTab();
      showMainTab('options');
    } else {
      showMainTab(h.main);
      if (h.main === 'state' && h.sub) selectStateSubTabByOriginalId(h.sub);
    }
  }

  window.addEventListener('hashchange', activateFromHash);

  // ----- Data loading -----
  function loadDisplayables() {
    return $.get('api/displayables', function (data) {
      if (data && data.items) buildStateTabs(data.items);
    }, 'json');
  }

  function subscribeOptions() {
    if (typeof DisplayableSse === 'undefined' || !DisplayableSse.onOptionsUpdate) return;
    DisplayableSse.onOptionsUpdate(function (data) {
      if (data && data.tabs) {
        const activeTab = activeOptionsTabId();
        buildOptionsTabs(data.tabs);
        attachOptionHandlers();
        if (activeTab) selectOptionsSubTabById(activeTab);
        setupMobileSubtabsFor('#optionsTab');
      }
    });
  }

  // ----- Init -----
  function initAfterLoad() {
    bindNavigationHandlers();
    activateFromHash();
    setupMobileSubtabsFor('#stateTab');
    setupMobileSubtabsFor('#optionsTab');
    ensureStateStreaming();
    subscribeOptions();
  }

  $.when(loadDisplayables()).done(initAfterLoad);

  // ----- Mobile sub-tabs (chevron dropdown) -----
  function setupMobileSubtabsFor(navSelector) {
    const $nav = $(navSelector);
    if ($nav.length === 0) return;
    const forId = $nav.attr('id');
    const containerSelector = '.mobile-subtabs[data-for="' + forId + '"]';
    let $container = $(containerSelector);
    const mobile = isMobileViewport();

    if (!mobile) {
      $nav.removeClass('d-none');
      if ($container.length) $container.addClass('d-none').removeClass('open');
      return;
    }

    // Mobile: ensure container exists
    if ($container.length === 0) {
      $container = $(
          '<div class="mobile-subtabs mb-2" data-for="' + forId + '">' +
          '<button type="button" class="btn btn-outline-secondary w-100 d-flex justify-content-between align-items-center mobile-subtabs-toggle" aria-expanded="false">' +
          '<span class="mobile-subtabs-label">Subtab</span>' +
          '<i class="bi bi-chevron-down mobile-subtabs-chevron"></i>' +
          '</button>' +
          '<div class="mobile-subtabs-menu card p-1 mt-1" role="menu" aria-hidden="true"></div>' +
          '</div>'
      );
      $nav.before($container);

      $container.on('click', '.mobile-subtabs-toggle', function () {
        const open = !$container.hasClass('open');
        $container.toggleClass('open', open);
        $container.find('.mobile-subtabs-toggle').attr('aria-expanded', String(open));
        $container.find('.mobile-subtabs-menu').attr('aria-hidden', String(!open));
      });

      $(document).on('click', function (e) {
        if (!$container.hasClass('open')) return;
        if ($(e.target).closest(containerSelector).length === 0) {
          $container.removeClass('open');
          $container.find('.mobile-subtabs-toggle').attr('aria-expanded', 'false');
          $container.find('.mobile-subtabs-menu').attr('aria-hidden', 'true');
        }
      });

      $container.on('click', '[data-target-tab]', function () {
        const target = $(this).attr('data-target-tab');
        const btn = $nav.find('button[data-bs-target="' + target + '"]')[0];
        if (btn) new bootstrap.Tab(btn).show();
        $container.removeClass('open');
        $container.find('.mobile-subtabs-toggle').attr('aria-expanded', 'false');
        $container.find('.mobile-subtabs-menu').attr('aria-hidden', 'true');
      });
    }

    $nav.addClass('d-none');
    $container.removeClass('d-none');

    // Rebuild menu items from current nav
    const items = $nav.find('button[data-bs-toggle="tab"]').map(function () {
      const $b = $(this);
      return {text: $b.text().trim(), target: $b.attr('data-bs-target'), active: $b.hasClass('active')};
    }).get();

    const $menu = $container.find('.mobile-subtabs-menu');
    $menu.empty();
    items.forEach(function (it) {
      const activeClass = it.active ? ' active' : '';
      $menu.append(
          '<div class="px-1 py-1">' +
          '<button type="button" class="btn btn-sm w-100 text-start mobile-subtabs-item' + activeClass + '" data-target-tab="' + it.target + '">' +
          $('<div/>').text(it.text).html() +
          '</button>' +
          '</div>'
      );
    });

    const active = items.find(function (it) {
      return it.active;
    }) || items[0];
    $container.find('.mobile-subtabs-label').text(active ? active.text : 'Subtab');
  }
});
