/* Runs only by evaluateJavascript on an allowlisted HTTPS top-level page.
 * No Java interface, cookie extraction, fetch, or local tool capability. */
(function () {
  'use strict';
  const C = __LION_CONFIG__;
  const token = __LION_TOKEN__;
  const action = __LION_ACTION__;
  const prompt = __LION_PROMPT__;
  function result(status, extra) { return JSON.stringify(Object.assign({status: status, token: token}, extra || {})); }
  if (location.protocol !== 'https:' || !C.hosts.includes(location.hostname)) return result('origin');
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
  function inputText(n) { return String(n && 'value' in n ? n.value : text(n)).replace(/\r\n/g, '\n').replace(/\u00a0/g, ' ').trim(); }
  function expectedText() { return prompt.replace(/\r\n/g, '\n').replace(/\u00a0/g, ' ').trim(); }
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
        last: '', changed: Date.now(), clicks: 0};
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
      const value = answerText(last);
      // Accept only a new assistant turn; never recycle or edit an old answer.
      const isNew = last && !state.baseline.includes(last) &&
        (list.length > state.baseline.length || !state.baselineText.includes(value));
      const stopping = !!find(C.stop);
      if (stopping) state.sawStop = true;
      if (!state.sent) {
        // Wait for observable site acknowledgement; merely calling click() is not enough.
        if ((!input || !inputText(input)) && (stopping || (isNew && value))) {
          state.sent = true;
        } else {
          return result(Date.now() - state.clickedAt > 30000 ? 'send_unconfirmed' : 'awaiting_ack');
        }
      }
      if (!isNew || !value) return result(stopping ? 'sent' : 'waiting', {generating: stopping});
      if (value !== state.last) { state.last = value; state.changed = Date.now(); }
      const completeInside = !!last.querySelector(C.complete);
      // Some sites put their copy controls in the parent assistant message container.
      const parent = last.parentElement;
      const completeNearby = !!parent && nodes(C.complete, parent).some(visible) &&
        nodes(C.answer, parent).length <= 1;
      const stable = Date.now() - state.changed >= 3000;
      const completed = !stopping && stable && (state.sawStop || completeInside || completeNearby);
      return result(completed ? 'complete' : 'streaming', {text: value, observedStop: state.sawStop});
    }
    return result('unknown_action');
  } catch (e) { return result('selector_error', {error: String(e.message || e).slice(0, 160)}); }
})();
