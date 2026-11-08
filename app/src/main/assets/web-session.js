/* Runs only by evaluateJavascript on an allowlisted HTTPS top-level page.
 * No Java interface, cookie extraction, fetch, or local tool capability. */
(function () {
  'use strict';
  const C = __LION_CONFIG__;
  const token = __LION_TOKEN__;
  const action = __LION_ACTION__;
  const prompt = __LION_PROMPT__;
  function result(status, extra) { return JSON.stringify(Object.assign({status: status, token: token}, extra || {})); }
  const pageInfo = {
    actualOrigin: location.href === 'about:blank' ? 'about:blank' :
      (location.origin && location.origin !== 'null' ? location.origin : location.protocol),
    host: location.hostname, protocol: location.protocol,
    readyState: document.readyState, expectedHosts: C.hosts
  };
  if (location.protocol !== 'https:' || !C.hosts.includes(location.hostname)) return result('origin', pageInfo);
  function visible(n) {
    if (!n) return false;
    try {
      if (n.getClientRects && n.getClientRects().length === 0) return false;
      const st = n.ownerDocument && n.ownerDocument.defaultView
        ? n.ownerDocument.defaultView.getComputedStyle(n) : getComputedStyle(n);
      return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
    } catch (e) { return !!n; }
  }
  function walkRoots(fn, root) {
    const start = root || document;
    fn(start);
    try {
      const all = start.querySelectorAll ? start.querySelectorAll('*') : [];
      for (let i = 0; i < all.length; i++) {
        if (all[i].shadowRoot) walkRoots(fn, all[i].shadowRoot);
      }
    } catch (e) {}
    if (!root) {
      try {
        Array.from(document.querySelectorAll('iframe')).forEach(function (f) {
          try { if (f.contentDocument) walkRoots(fn, f.contentDocument); } catch (e) {}
        });
      } catch (e) {}
    }
  }
  function nodes(selector, root) {
    const out = [];
    let invalid = null;
    walkRoots(function (r) {
      try { r.querySelectorAll(selector).forEach(function (n) { out.push(n); }); }
      catch (e) { if (!invalid) invalid = e; }
    }, root);
    if (invalid) throw invalid;
    return out;
  }
  function find(selector) { return nodes(selector).find(visible); }
  function findInput() {
    const explicit = find(C.input);
    if (explicit && !isDisabled(explicit) && !explicit.readOnly) return explicit;
    const all = nodes("textarea, [contenteditable='true'][role='textbox'], [contenteditable='true']").filter(function (n) {
      return visible(n) && !isDisabled(n) && !n.readOnly;
    });
    if (!all.length) return explicit || null;
    const vh = (window.innerHeight || 800);
    const bottom = all.filter(function (n) {
      try { return n.getBoundingClientRect().top > vh * 0.35; } catch (e) { return true; }
    });
    return (bottom.length ? bottom[bottom.length - 1] : all[all.length - 1]);
  }
  function isUserTurn(n) {
    let el = n;
    for (let i = 0; i < 6 && el; i++, el = el.parentElement) {
      const cls = ((el.getAttribute && el.getAttribute('class')) || '') + ' ' +
        ((el.getAttribute && el.getAttribute('data-message-role')) || '') + ' ' +
        ((el.getAttribute && el.getAttribute('data-message-author-role')) || '');
      if (/\bjustify-end\b|\bitems-end\b/.test(cls)) return true;
      if (/(^|[\s_-])user([\s_-]|$)/i.test(cls) && !/assistant/i.test(cls)) return true;
    }
    return false;
  }
  function listReversed(nodes) {
    if (!nodes.length) return false;
    let el = nodes[0].parentElement;
    for (let i = 0; i < 8 && el; i++, el = el.parentElement) {
      try {
        const view = (el.ownerDocument && el.ownerDocument.defaultView) || window;
        const d = (view.getComputedStyle(el).flexDirection || '').toLowerCase();
        if (d.indexOf('reverse') !== -1) return true;
      } catch (e) {}
    }
    return false;
  }
  function newestUnseen(list, baselineText) {
    const unseen = [];
    for (let i = 0; i < list.length; i++) {
      const t = answerText(list[i]);
      if (t && baselineText.indexOf(t) === -1) unseen.push(list[i]);
    }
    if (!unseen.length) return {node: null, value: ''};
    const reversed = listReversed(list);
    if (C.joinAnswers === true) {
      // A reply may be split across several answer nodes: join every new one in reading order,
      // skipping an echo of our own prompt.
      const mine = collapsed(expectedText());
      const parts = (reversed ? unseen.slice().reverse() : unseen)
        .map(function (n) { return {n: n, t: answerText(n)}; })
        .filter(function (x) { return x.t && collapsed(x.t) !== mine; });
      if (!parts.length) return {node: null, value: ''};
      return {node: parts[parts.length - 1].n, value: parts.map(function (x) { return x.t; }).join('\n\n')};
    }
    const node = reversed ? unseen[0] : unseen[unseen.length - 1];
    return {node: node, value: answerText(node)};
  }
  function answers() { return nodes(C.answer).filter(function (n) { return visible(n) && !isUserTurn(n); }); }
  function answerText(n) {
    if (!n) return '';
    const clone = n.cloneNode(true);
    clone.querySelectorAll('button, svg, script, style, [aria-hidden="true"]').forEach(x => x.remove());
    clone.querySelectorAll('pre').forEach(pre => {
      const code = pre.querySelector('code') || pre;
      const language = (code.className || '').match(/language-([a-zA-Z0-9_+-]+)/);
      pre.replaceWith(document.createTextNode('\n```' + (language ? language[1] : '') + '\n' +
        code.textContent + '\n```\n'));
    });
    clone.querySelectorAll('p, div, li, h1, h2, h3, h4, br').forEach(x => {
      if (x.tagName === 'BR') x.replaceWith(document.createTextNode('\n'));
      else x.appendChild(document.createTextNode('\n'));
    });
    return (clone.textContent || '').trim();
  }

  function text(n) { return n ? (n.innerText || n.textContent || '').trim() : ''; }
  // Chromium innerText inserts an extra visual newline for <div><br></div>.
  // Read editor block structure; do not rewrite the user's text or collapse arbitrary whitespace.
  function editableText(n) {
    if (n.nodeType === 3) return n.nodeValue || '';
    const block = x => x.nodeType === 1 && /^(DIV|P|LI|BLOCKQUOTE|PRE|H[1-6])$/.test(x.tagName);
    if (n.nodeType === 1 && n.tagName === 'BR') {
      return n.parentNode && block(n.parentNode) && n.parentNode.childNodes.length === 1 ? '' : '\n';
    }
    let value = '', previousBlock = false;
    Array.from(n.childNodes || []).forEach((child, index) => {
      const isBlock = block(child);
      if (index > 0 && (isBlock || previousBlock)) value += '\n';
      value += editableText(child);
      previousBlock = isBlock;
    });
    return value;
  }
  function inputText(n) {
    const value = n && 'value' in n ? n.value : n && n.isContentEditable ? editableText(n) : text(n);
    return String(value).replace(/\r\n/g, '\n').replace(/\u00a0/g, ' ').trim();
  }
  function expectedText() { return prompt.replace(/\r\n/g, '\n').replace(/\u00a0/g, ' ').trim(); }
  function collapsed(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
  function isDisabled(n) {
    return !n || n.disabled || n.getAttribute('aria-disabled') === 'true' || n.getAttribute('aria-busy') === 'true';
  }
  function labelOf(n) {
    return ((n.getAttribute('aria-label') || n.title || n.getAttribute('data-testid') ||
      n.getAttribute('data-test-id') || n.innerText || '') + '').trim();
  }
  function rectOf(n) {
    try {
      const r = n.getBoundingClientRect();
      return {x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height};
    } catch (e) { return {x: 0, y: 0, w: 0, h: 0}; }
  }
  function findSend() {
    const explicit = find(C.send);
    if (explicit && !isDisabled(explicit)) return explicit;
    const exact = /^(send|send message|submit|إرسال|ارسل|envoyer|senden)$/i;
    const partial = /(send|submit|إرسال|paper-airplane|arrow-up)/i;
    const stopish = /(stop|إيقاف|cancel generating)/i;
    const buttons = nodes('button, [role="button"], [data-testid*="send"], [data-test-id*="send"]');
    const labeled = buttons.find(function (b) {
      if (!visible(b) || isDisabled(b)) return false;
      const lab = labelOf(b);
      return exact.test(lab) && !stopish.test(lab);
    });
    if (labeled) return labeled;
    const input = findInput();
    if (input) {
      const form = input.closest && input.closest('form');
      if (form) {
        const sub = Array.from(form.querySelectorAll('button[type="submit"], button:not([type]), [role="button"]'))
          .find(function (b) { return visible(b) && !isDisabled(b) && !stopish.test(labelOf(b)); });
        if (sub) return sub;
      }
      let root = input.parentElement;
      for (let i = 0; i < 8 && root; i++, root = root.parentElement) {
        const cands = nodes('button, [role="button"]', root).filter(function (b) {
          return visible(b) && !isDisabled(b) && !stopish.test(labelOf(b));
        });
        const sendish = cands.find(function (b) { return partial.test(labelOf(b)); });
        if (sendish) return sendish;
        if (cands.length === 1) return cands[0];
      }
    }
    return (explicit && !isDisabled(explicit)) ? explicit : null;
  }
  function pressEnter(el) {
    if (!el) return false;
    try { el.focus({preventScroll: true}); } catch (e) { try { el.focus(); } catch (e2) {} }
    const opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true, composed: true};
    try { el.dispatchEvent(new KeyboardEvent('keydown', opts)); } catch (e) {}
    try { el.dispatchEvent(new KeyboardEvent('keypress', opts)); } catch (e) {}
    try { el.dispatchEvent(new KeyboardEvent('keyup', opts)); } catch (e) {}
    return true;
  }
  function writeInput(input, value) {
    input.focus({preventScroll: true});
    if (input.isContentEditable) {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(input);
      selection.removeAllRanges(); selection.addRange(range);
      if (typeof document.execCommand !== 'function' || !document.execCommand('insertText', false, value)) {
        input.textContent = value;
        input.dispatchEvent(new InputEvent('input', {bubbles: true, composed: true, inputType: 'insertText', data: value}));
      }
      return;
    }
    // Preferred: let the browser insert the text as a real edit (beforeinput + input), which React-controlled
    // composers register in their state (otherwise the send button stays disabled with text in the box).
    try {
      if (typeof document.execCommand === 'function') {
        input.focus({preventScroll: true});
        input.select();
        if (document.execCommand('insertText', false, value) &&
            input.value.replace(/\r\n/g, '\n') === String(value).replace(/\r\n/g, '\n')) return;
      }
    } catch (e) {}
    const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    try { if (input._valueTracker) input._valueTracker.setValue(''); } catch (e) {}
    setter.call(input, value);
    try {
      input.dispatchEvent(new InputEvent('input', {bubbles: true, cancelable: true, composed: true, inputType: 'insertText', data: value}));
    } catch (e) {
      input.dispatchEvent(new Event('input', {bubbles: true}));
    }
    input.dispatchEvent(new Event('change', {bubbles: true}));
  }
  // Delivery proof that does not depend on any site-specific assistant selector:
  // after the editor clears, the posted user turn becomes visible in the page text.
  function postedInPage(editor) {
    // Editor missing or cleared: only a posted turn visible in page text proves delivery.
    if (!editor || !inputText(editor)) {
      const wanted = collapsed(expectedText()).slice(0, 80);
      if (wanted.length < 3) return false;
      return collapsed(pageText(editor)).indexOf(wanted) !== -1;
    }
    return false;
  }
  // ===== التقاط احتياطي عام عند عدم مطابقة محددات الموقع =====
  // نص الصفحة بدون حقل الكتابة نفسه (حتى لا يُحسب نص المسودة داخل المحرر كأنه رسالة ظاهرة).
  function pageText(skip) {
    const root = document.querySelector('main') || document.body;
    const out = [];
    (function walk(n) {
      if (!n || n === skip) return;
      if (n.nodeType === 3) { out.push(n.nodeValue); return; }
      if (n.nodeType !== 1) return;
      const tag = n.tagName;
      if (/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|SVG)$/i.test(tag)) return;
      if (n.hidden || n.getAttribute('aria-hidden') === 'true') return;
      const block = /^(DIV|P|LI|UL|OL|SECTION|ARTICLE|MAIN|HEADER|FOOTER|H[1-6]|PRE|BLOCKQUOTE|TABLE|TR|BR|BUTTON)$/i.test(tag);
      if (block) out.push('\n');
      for (let c = n.firstChild; c; c = c.nextSibling) walk(c);
      if (n.shadowRoot) walk(n.shadowRoot);
      if (block) out.push('\n');
    })(root);
    return out.join('').replace(/\u00a0/g, ' ').replace(/[ \t]+\n/g, '\n').replace(/\n{2,}/g, '\n');
  }
  function commonPrefix(a, b) {
    const n = Math.min(a.length, b.length); let i = 0;
    while (i < n && a.charCodeAt(i) === b.charCodeAt(i)) i++;
    return i;
  }
  function commonSuffix(a, b) {
    const n = Math.min(a.length, b.length); let i = 0;
    while (i < n && a.charCodeAt(a.length - 1 - i) === b.charCodeAt(b.length - 1 - i)) i++;
    return i;
  }
  function escapeRe(w) { return w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  function lastTailMatch(value) {
    const words = prompt.replace(/\u00a0/g, ' ').split(/\s+/).filter(Boolean).slice(-12);
    if (!words.length) return null;
    const re = new RegExp(words.map(escapeRe).join('\\s+'), 'g');
    let m = null, last = null;
    while ((m = re.exec(value)) !== null) {
      last = {start: m.index, end: m.index + m[0].length};
      if (m.index === re.lastIndex) re.lastIndex++;
    }
    return last;
  }
  // ما أُضيف إلى الصفحة بعد الإرسال = ما بين ما قبله وما بعده (واجهة الصفحة الثابتة تُستبعد تلقائياً).
  function fallbackState(st, skip) {
    const now = pageText(skip);
    const before = st.before || '';
    const p = commonPrefix(before, now);
    const sfx = commonSuffix(before.slice(p), now.slice(p));
    const inserted = now.slice(p, now.length - sfx);
    const m = lastTailMatch(inserted);
    if (!m) return {shown: false, answer: ''};
    const after = inserted.slice(m.end).trim();
    const prior = inserted.slice(0, m.start).trim();
    // Newest-first lists (Arena flex-col-reverse) put the reply BEFORE the user turn.
    let answer = after.length >= prior.length ? after : prior;
    if (collapsed(answer) === collapsed(expectedText())) answer = '';
    return {shown: true, answer: answer};
  }
  function genericStop() {
    return nodes('button, [role="button"]').some(function (b) {
      if (!visible(b)) return false;
      const label = (b.getAttribute('aria-label') || b.title || b.innerText || '').trim();
      return /^(stop|stop generating|stop response|stop generation|cancel generating|إيقاف|توقف|إيقاف التوليد)\b/i.test(label);
    });
  }
  try {
    if (action === 'diagnose') {
      function describe(n) {
        const entry = {tag: n.tagName.toLowerCase()};
        ['id', 'class', 'role', 'data-testid', 'data-message-role', 'data-message-author-role', 'type', 'contenteditable'].forEach(k => {
          const v = n.getAttribute(k);
          if (v) entry[k] = v.slice(0, 120);
        });
        const label = n.getAttribute('aria-label') || '';
        // Site conversation titles may include prompt text in aria-label: never copy arbitrary labels.
        if (/^(send|send message|submit|stop|stop generating|stop generation|stop response|add files|copy|copy code|copy response|open sidebar)$/i.test(label)) entry['aria-label'] = label;
        else if (label) entry.ariaLabelOmitted = true;
        entry.disabled = !!n.disabled || n.getAttribute('aria-disabled') === 'true';
        if ('value' in n) entry.valueLength = n.value.length;
        entry.icons = Array.from(n.querySelectorAll('svg')).slice(0,3).map(svg => ({
          class: (svg.getAttribute('class') || '').slice(0,120),
          paths: Array.from(svg.querySelectorAll('path')).slice(0,3).map(path => (path.getAttribute('d') || '').slice(0,160))
        }));
        return entry;
      }
      return result('diagnostic', {
        host: location.hostname,
        actualOrigin: pageInfo.actualOrigin,
        readyState: document.readyState,
        visibility: document.visibilityState,
        documentFocused: document.hasFocus(),
        inputs: nodes('textarea, [contenteditable="true"]').filter(visible).slice(0, 8).map(describe),
        buttons: nodes('button, [role="button"]').filter(visible).slice(-40).map(describe),
        responses: nodes('[data-message-role], [data-message-author-role], [data-role="assistant"], [class*="assistant"], [class*="markdown"], [class*="prose"], [class*="response"], model-response').filter(visible).slice(-20).map(describe)
      });
    }
    function looksLikeLogin() {
      const title = (document.title || '').toLowerCase();
      if (/sign in|log in|login|تسجيل الدخول|connexion|anmelden/.test(title)) return true;
      if (location.pathname && /\/(login|signin|sign-in|log-in|auth|accounts)\b/i.test(location.pathname)) return true;
      return !!find('input[type="password"]');
    }
    const input = findInput();
    if (action === 'probe') {
      if (find(C.stop)) return result('site_busy');
      if (looksLikeLogin()) return result('needs_input');
      if (!input) return result('needs_input');
      if (input.disabled || input.readOnly || input.getAttribute('aria-disabled') === 'true') return result('input_disabled');
      return result('ready');
    }
    let state = window.__lionSessionRequest;
    if (action === 'prepare') {
      if (looksLikeLogin()) return result('needs_input');
      if (!input) return result('needs_input');
      if (state && state.token === token) return result(state.sent ? 'sent' : state.clicked ? 'clicked' : 'prepared');
      if (find(C.stop)) return result('site_busy');
      // App Send owns the composer: leftover test drafts and previous unsent
      // prompts must be replaced, otherwise the message never leaves the app.
      const wanted = expectedText();
      const current = inputText(input);
      const old = answers();
      state = {token: token, baseline: old, baselineText: old.map(answerText), sent: false, clicked: false, clickedAt: 0, sawStop: false,
        last: '', changed: Date.now(), clicks: 0, before: pageText(input), tapped: false,
        baselineErr: C.error ? nodes(C.error).slice() : []};
      window.__lionSessionRequest = state;
      if (current !== wanted) {
        writeInput(input, prompt);
        if (inputText(input) !== wanted) {
          delete window.__lionSessionRequest;
          return result('input_rejected', {focused: document.activeElement === input, editable: input.isContentEditable === true});
        }
      }
      return result('prepared', rectOf(input));
    }
    if (!state || state.token !== token) return result('lost');
    if (action === 'cancel') {
      // No automatic clicks on cancellation: stop locally, don't risk another send.
      delete window.__lionSessionRequest;
      return result('cancelled');
    }
    if (action === 'locate') {
      if (state.sent) return result('sent');
      if (state.clicked) return result('located', state.tap || {});
      if (find(C.stop)) return result('site_busy');
      if (!input || inputText(input) !== expectedText()) return result(input && inputText(input) ? 'draft_changed' : 'input_rejected');
      const button = findSend();
      if (!button) return result('needs_send');
      const tap = rectOf(button);
      if (!(tap.w > 0 && tap.h > 0)) return result('needs_send');
      state.clicked = true; state.clickedAt = Date.now(); state.clicks++; state.tap = tap; state.tapped = true;
      return result('located', tap);
    }
    if (action === 'enter') {
      if (state.sent) return result('sent');
      if (state.clicked && !state.fromEnter) return result(state.tapped ? 'located' : 'clicked');
      if (find(C.stop)) return result('site_busy');
      if (!input || inputText(input) !== expectedText()) return result(input && inputText(input) ? 'draft_changed' : 'input_rejected');
      state.clicked = true; state.clickedAt = Date.now(); state.clicks++; state.fromEnter = true;
      pressEnter(input);
      return result('entered');
    }
    if (action === 'submit') {
      if (state.sent) return result('sent');
      if (state.clicked) return result(state.tapped ? 'located' : (state.fromEnter ? 'entered' : 'clicked'));
      if (find(C.stop)) return result('site_busy');
      if (!input || inputText(input) !== expectedText()) return result(input && inputText(input) ? 'draft_changed' : 'input_rejected');
      const button = find(C.send);
      if (!button || button.disabled || button.getAttribute('aria-disabled') === 'true') return result('needs_send');
      state.clicked = true; state.clickedAt = Date.now(); state.clicks++;
      button.click(); // Click is not delivery acknowledgement. Never click this token twice.
      return result('clicked');
    }
    if (action === 'poll') {
      if (!state.clicked) return result('not_sent');
      const list = answers();
      const pick = newestUnseen(list, state.baselineText);
      const last = pick.node;
      let value = pick.value;
      let isNew = !!value;
      const fb = fallbackState(state, input);
      let viaFallback = false;
      const explicitStop = !!find(C.stop);
      if (explicitStop) state.sawStop = true;
      const stopping = explicitStop || (state.sent && genericStop());
      const info = 'a=' + list.length + ' new=' + (isNew ? 1 : 0) + ' shown=' + (fb.shown ? 1 : 0) +
        ' stop=' + (stopping ? 1 : 0) + ' len=' + (value || '').length;
      if (C.error) {
        // The site answered with its own error message instead of a reply: surface it, don't treat it as an answer.
        const errs = nodes(C.error).filter(function (n) { return visible(n) && state.baselineErr.indexOf(n) === -1; })
          .map(text).filter(Boolean);
        if (errs.length && !(isNew && value)) return result('site_error', {text: errs[errs.length - 1].slice(0, 400), diag: info});
      }
      if (!state.sent) {
        // Delivery proof: a genuinely new assistant text, a stop control this click
        // started, or our prompt posted in the page after the composer cleared.
        // Leftover page chrome matching the prompt is not acknowledgement.
        if ((isNew && value) || explicitStop || postedInPage(input)) {
          state.sent = true;
        } else {
          return result(Date.now() - state.clickedAt > 45000 ? 'send_unconfirmed' : 'awaiting_ack', {diag: info});
        }
      }
      if (C.fallback !== false && (!isNew || !value) && fb.shown && fb.answer && state.baselineText.indexOf(fb.answer) === -1) {
        value = fb.answer;
        isNew = true;
        viaFallback = true;
      }
      if (!isNew || !value) return result(stopping ? 'sent' : 'waiting', {generating: stopping, diag: info});
      if (value !== state.last) { state.last = value; state.changed = Date.now(); }
      const completeInside = !viaFallback && !!last && !!last.querySelector(C.complete);
      const parent = !viaFallback && last ? last.parentElement : null;
      const completeNearby = !!parent && nodes(C.complete, parent).some(visible) &&
        nodes(C.answer, parent).length <= 1;
      const age = Date.now() - state.changed;
      const settle = Number(C.settleMs) > 0 ? Number(C.settleMs) : 2000;
      // Return to the app as soon as a NEW answer stops growing. Copy/stop is
      // enough after 0.7s; otherwise 2s of stillness. Never wait 5s+ on a finished reply.
      const completed = !stopping && value.length > 0 && (
        (age >= 700 && (state.sawStop || completeInside || completeNearby)) ||
        age >= settle
      );
      return result(completed ? 'complete' : 'streaming',
        {text: value, observedStop: state.sawStop, viaFallback: viaFallback, diag: info});
    }
    return result('unknown_action');
  } catch (e) { return result('selector_error', {error: String(e.message || e).slice(0, 160)}); }
})();
