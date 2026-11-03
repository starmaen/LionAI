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
  function visible(n) { return !!n && n.getClientRects().length > 0 && getComputedStyle(n).visibility !== 'hidden'; }
  function nodes(selector, root) { return Array.from((root || document).querySelectorAll(selector)); }
  function find(selector) { return nodes(selector).find(visible); }
  function answers() { return nodes(C.answer).filter(visible); }
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
  // Delivery proof that does not depend on any site-specific assistant selector:
  // after the editor clears, the posted user turn becomes visible in the page text.
  function postedInPage(editor) {
    // Editor missing or cleared: only a posted turn visible in page text proves delivery.
    if (!editor || !inputText(editor)) {
      const wanted = collapsed(expectedText()).slice(0, 80);
      if (wanted.length < 3) return false;
      let bodyText = '';
      try { if (document.body) bodyText = document.body.innerText; }
      catch (e) { return false; }
      const body = collapsed(bodyText);
      return body.indexOf(wanted) !== -1;
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
    return {shown: !!m, answer: m ? inserted.slice(m.end).trim() : ''};
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
    const input = find(C.input);
    if (action === 'probe') {
      if (find(C.stop)) return result('site_busy');
      if (!input) return result('needs_input');
      if (input.disabled || input.readOnly || input.getAttribute('aria-disabled') === 'true') return result('input_disabled');
      return result('ready');
    }
    let state = window.__lionSessionRequest;
    if (action === 'prepare') {
      if (!input) return result('needs_input');
      if (state && state.token === token) return result(state.sent ? 'sent' : state.clicked ? 'clicked' : 'prepared');
      if (find(C.stop)) return result('site_busy');
      if (text(input) || (input.value || '').trim()) return result('draft');
      const old = answers();
      state = {token: token, baseline: old, baselineText: old.map(answerText), sent: false, clicked: false, clickedAt: 0, sawStop: false,
        last: '', changed: Date.now(), clicks: 0, before: pageText(input)};
      window.__lionSessionRequest = state;
      input.focus({preventScroll: true});
      if (input.isContentEditable) {
        // execCommand updates the editor's internal selection and emits an input event.
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(input);
        selection.removeAllRanges(); selection.addRange(range);
        if (typeof document.execCommand !== 'function' || !document.execCommand('insertText', false, prompt)) {
          input.textContent = prompt;
          input.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: prompt}));
        }
      } else {
        const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
        setter.call(input, prompt);
        input.dispatchEvent(new Event('input', {bubbles: true}));
        input.dispatchEvent(new Event('change', {bubbles: true}));
      }
      if (inputText(input) !== expectedText()) {
        delete window.__lionSessionRequest;
        return result('input_rejected', {focused: document.activeElement === input, editable: input.isContentEditable === true});
      }
      return result('prepared');
    }
    if (!state || state.token !== token) return result('lost');
    if (action === 'cancel') {
      // No automatic clicks on cancellation: stop locally, don't risk another send.
      delete window.__lionSessionRequest;
      return result('cancelled');
    }
    if (action === 'submit') {
      if (state.sent) return result('sent');
      if (state.clicked) return result('clicked');
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
      const last = list[list.length - 1];
      let value = answerText(last);
      // Accept only a new assistant turn; never recycle or edit an old answer.
      let isNew = last && !state.baseline.includes(last) &&
        (list.length > state.baseline.length || !state.baselineText.includes(value));
      // Fallback: the site's selectors found no new turn, but our message appeared on the page.
      // We then read what the page added after it (fixed page chrome is excluded by diffing).
      const fb = fallbackState(state, input);
      let viaFallback = false;
      if ((!isNew || !value) && fb.shown && fb.answer) { value = fb.answer; isNew = true; viaFallback = true; }
      const explicitStop = !!find(C.stop);
      if (explicitStop) state.sawStop = true;
      const stopping = explicitStop || (state.sent && genericStop());
      const info = 'a=' + list.length + ' fb=' + (viaFallback ? 1 : 0) + ' shown=' + (fb.shown ? 1 : 0) +
        ' stop=' + (stopping ? 1 : 0) + ' len=' + (value || '').length;
      if (!state.sent) {
        // A new assistant turn acknowledges delivery even if the site keeps the draft.
        // A visible stop button means this click started generation (submit refused to
        // click while the site was already busy). A cleared editor whose text now shows
        // as a posted turn, or our message appearing outside the composer, is delivery proof.
        if ((isNew && value) || explicitStop || postedInPage(input) || fb.shown) {
          state.sent = true;
        } else {
          return result(Date.now() - state.clickedAt > 45000 ? 'send_unconfirmed' : 'awaiting_ack', {diag: info});
        }
      }
      if (!isNew || !value) return result(stopping ? 'sent' : 'waiting', {generating: stopping, diag: info});
      if (value !== state.last) { state.last = value; state.changed = Date.now(); }
      const completeInside = !viaFallback && !!last && !!last.querySelector(C.complete);
      // Some sites put their copy controls in the parent assistant message container.
      const parent = !viaFallback && last ? last.parentElement : null;
      const completeNearby = !!parent && nodes(C.complete, parent).some(visible) &&
        nodes(C.answer, parent).length <= 1;
      const age = Date.now() - state.changed;
      // Selector path: a stalled stream is never a completed answer without completion evidence.
      // Fallback path (no site selectors to trust): text unchanged for 7s with no generating indicator.
      const completed = !stopping && ((age >= 3000 && (state.sawStop || completeInside || completeNearby)) ||
        (viaFallback && age >= 7000));
      return result(completed ? 'complete' : 'streaming',
        {text: value, observedStop: state.sawStop, viaFallback: viaFallback, diag: info});
    }
    return result('unknown_action');
  } catch (e) { return result('selector_error', {error: String(e.message || e).slice(0, 160)}); }
})();
