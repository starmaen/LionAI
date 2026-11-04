const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/web-session.js'), 'utf8');
function page(url = 'https://claude.ai/') {
  const dom = new JSDOM('<textarea id="input"></textarea><button id="send">send</button><main id="messages"></main>',
    {url, runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window;
  w.HTMLElement.prototype.getClientRects = function() { return this.hidden ? [] : [{}]; };
  let clock = 1000, clicks = 0;
  w.Date.now = () => clock;
  w.document.querySelector('#send').onclick = () => { clicks++; w.document.querySelector('#input').value = ''; };
  const config = {id:'claude_web', hosts:['claude.ai'],input:'#input',send:'#send',answer:'.assistant',stop:'.stop',complete:'.copy'};
  function run(action, prompt='hello', token='token1') {
    return JSON.parse(w.eval(source.replace('__LION_CONFIG__', JSON.stringify(config))
      .replace('__LION_TOKEN__', JSON.stringify(token)).replace('__LION_ACTION__', JSON.stringify(action))
      .replace('__LION_PROMPT__', JSON.stringify(prompt))));
  }
  function answer(value, copy = false) {
    const n = w.document.createElement('article'); n.className='assistant'; n.textContent=value;
    if(copy) {const b=w.document.createElement('button');b.className='copy';n.append(b);}
    w.document.querySelector('#messages').append(n); return n;
  }
  return {w,config,run,answer,clicks:()=>clicks,advance:(n)=>clock+=n, close:()=>dom.window.close()};
}
function withPage(fn, url) {const p=page(url);try{fn(p);}finally{p.close();}}

test('prepares text, emits one click, and never repeats a sent token',()=>withPage(p=>{
  assert.equal(p.run('probe').status,'ready');
  assert.equal(p.run('prepare').status,'prepared');
  assert.equal(p.w.document.querySelector('#input').value,'hello');
  assert.equal(p.run('prepare').status,'prepared');
  assert.equal(p.run('submit').status,'clicked');
  assert.equal(p.run('submit').status,'clicked');
  assert.equal(p.clicks(),1);
}));
test('requires a new assistant response and stable completion evidence',()=>withPage(p=>{
  p.answer('old reply',true);
  p.run('prepare');p.run('submit');p.advance(5000);
  assert.equal(p.run('poll').status,'awaiting_ack');
  const n=p.answer('new reply',true);
  assert.equal(p.run('poll').status,'streaming');
  p.advance(3100);
  assert.equal(p.run('poll').status,'complete');
  assert.equal(p.run('poll').text,'new reply');
}));
test('stable text alone is not considered completed',()=>withPage(p=>{
  p.run('prepare');p.run('submit');p.answer('partial');p.run('poll');p.advance(60000);
  assert.equal(p.run('poll').status,'streaming');
}));
test('observes generating indicator and waits until it stops',()=>withPage(p=>{
  p.run('prepare');p.run('submit');p.answer('result');
  const stop=p.w.document.createElement('button');stop.className='stop';p.w.document.body.append(stop);
  p.run('poll');p.advance(5000);assert.equal(p.run('poll').status,'streaming');
  stop.remove();assert.equal(p.run('poll').status,'complete');
}));
test('never overwrites a user draft',()=>withPage(p=>{
  p.w.document.querySelector('#input').value='my draft';
  assert.equal(p.run('prepare').status,'draft');assert.equal(p.clicks(),0);
  assert.equal(p.w.document.querySelector('#input').value,'my draft');
}));
test('detects changed drafts and disabled or missing send buttons',()=>withPage(p=>{
  p.run('prepare');const b=p.w.document.querySelector('#send');b.disabled=true;
  assert.equal(p.run('submit').status,'needs_send');b.disabled=false;
  p.w.document.querySelector('#input').value='changed';
  assert.equal(p.run('submit').status,'draft_changed');assert.equal(p.clicks(),0);
}));
test('refuses hostile origins before interacting with DOM',()=>withPage(p=>{
  assert.equal(p.run('prepare').status,'origin');assert.equal(p.clicks(),0);
},'https://claude.ai.evil.example/'));
test('rejects a stale token and a lost page request',()=>withPage(p=>{
  p.run('prepare');p.run('submit');
  assert.equal(p.run('poll','hello','wrong').status,'lost');
  p.run('cancel');assert.equal(p.run('poll').status,'lost');
}));
test('invalid CSS is reported rather than silently capturing body',()=>withPage(p=>{
  p.config.input='[';assert.equal(p.run('prepare').status,'selector_error');
}));
test('JSON escaping preserves adversarial text without executing it',()=>withPage(p=>{
  const text='"; window.hacked=true; //\n</script> مرحبا';
  assert.equal(p.run('prepare',text).status,'prepared');
  assert.equal(p.w.document.querySelector('#input').value,text);
  assert.equal(p.w.hacked,undefined);
}));
test('a real additional turn may repeat the same response text',()=>withPage(p=>{
  p.answer('تم',true);p.run('prepare');p.run('submit');p.answer('تم',true);
  p.run('poll');p.advance(4000);assert.equal(p.run('poll').status,'complete');
}));
test('preserves code fences and removes copy button text from returned answer',()=>withPage(p=>{
  p.run('prepare');p.run('submit');const n=p.answer('',true);
  n.innerHTML='<p>Code:</p><pre><code class="language-python">print(1)</code></pre><button class="copy">Copy code</button>';
  p.run('poll');p.advance(4000);const r=p.run('poll');
  assert.equal(r.status,'complete');assert.ok(r.text.includes('```python\nprint(1)\n```'));
  assert.ok(!r.text.includes('Copy code'));
}));
test('diagnostics collect structure without messages, input values, cookies or links',()=>withPage(p=>{
  p.w.document.querySelector('#input').value='private input';p.answer('private answer');
  p.w.document.cookie='secret=session-cookie';
  const raw=JSON.stringify(p.run('diagnose'));
  assert.ok(raw.includes('diagnostic'));assert.ok(raw.includes('textarea'));
  assert.ok(!raw.includes('private input'));assert.ok(!raw.includes('private answer'));
  assert.ok(!raw.includes('session-cookie'));assert.equal(p.clicks(),0);
}));
test('contenteditable editor is focused before editing and checked before submit',()=>withPage(p=>{
  const old=p.w.document.querySelector('#input');const editor=p.w.document.createElement('div');
  editor.id='input';editor.setAttribute('contenteditable','true');editor.tabIndex=0;
  Object.defineProperty(editor,'isContentEditable',{get:()=>true});old.replaceWith(editor);
  let edits=0;
  p.w.document.execCommand=(name,unused,value)=>{
    assert.equal(name,'insertText');assert.equal(p.w.document.activeElement,editor);
    editor.textContent=value;edits++;return true;
  };
  assert.equal(p.run('prepare','Arabic مرحبا\nsecond line').status,'prepared');
  assert.equal(editor.textContent,'Arabic مرحبا\nsecond line');
  assert.equal(p.run('submit','Arabic مرحبا\nsecond line').status,'clicked');
  assert.equal(edits,1);assert.equal(p.clicks(),1);
}));
test('editor claiming success but rejecting text is not considered prepared',()=>withPage(p=>{
  const old=p.w.document.querySelector('#input');const editor=p.w.document.createElement('div');
  editor.id='input';editor.tabIndex=0;Object.defineProperty(editor,'isContentEditable',{get:()=>true});old.replaceWith(editor);
  p.w.document.execCommand=()=>true;
  assert.equal(p.run('prepare').status,'input_rejected');assert.equal(p.clicks(),0);
}));
test('framework clearing inserted text is caught even if send button becomes disabled',()=>withPage(p=>{
  p.run('prepare');p.w.document.querySelector('#input').value='';p.w.document.querySelector('#send').disabled=true;
  assert.equal(p.run('submit').status,'input_rejected');assert.equal(p.clicks(),0);
}));
test('disabled and readonly textareas never pass readiness checks',()=>withPage(p=>{
  const input=p.w.document.querySelector('#input');input.disabled=true;
  assert.equal(p.run('probe').status,'input_disabled');input.disabled=false;input.readOnly=true;
  assert.equal(p.run('probe').status,'input_disabled');assert.equal(p.clicks(),0);
}));
test('click alone is not acknowledgement and never triggers another click',()=>withPage(p=>{
  p.w.document.querySelector('#send').onclick=()=>{};
  p.run('prepare');assert.equal(p.run('submit').status,'clicked');
  assert.equal(p.run('poll').status,'awaiting_ack');
  assert.equal(p.run('submit').status,'clicked');
  p.advance(31000);assert.equal(p.run('poll').status,'send_unconfirmed');
}));
test('busy site is rejected before overwriting the composer',()=>withPage(p=>{
  const b=p.w.document.createElement('button');b.className='stop';p.w.document.body.append(b);
  assert.equal(p.run('probe').status,'site_busy');assert.equal(p.run('prepare').status,'site_busy');
  assert.equal(p.w.document.querySelector('#input').value,'');assert.equal(p.clicks(),0);
}));
test('delivery is acknowledged when the site clears input and starts generating',()=>withPage(p=>{
  p.run('prepare');p.run('submit');
  assert.equal(p.run('poll').status,'awaiting_ack');
  const b=p.w.document.createElement('button');b.className='stop';p.w.document.body.append(b);
  assert.equal(p.run('poll').status,'sent');assert.equal(p.clicks(),1);
}));
test('diagnostics redact conversation titles stored in aria-label',()=>withPage(p=>{
  const b=p.w.document.createElement('button');b.setAttribute('aria-label','PRIVATE MESSAGE, model Max');p.w.document.body.append(b);
  const raw=JSON.stringify(p.run('diagnose'));assert.ok(!raw.includes('PRIVATE MESSAGE'));assert.ok(raw.includes('ariaLabelOmitted'));
}));
test('contenteditable empty block serializes as one empty line without changing DOM',()=>withPage(p=>{
  const old=p.w.document.querySelector('#input');const editor=p.w.document.createElement('div');
  editor.id='input';editor.tabIndex=0;Object.defineProperty(editor,'isContentEditable',{get:()=>true});old.replaceWith(editor);
  p.w.document.execCommand=()=>{editor.innerHTML='first<div>second</div><div><br></div><div>last</div>';return true;};
  const prompt='first\nsecond\n\nlast';
  assert.equal(p.run('prepare',prompt).status,'prepared');
  assert.equal(editor.innerHTML,'first<div>second</div><div><br></div><div>last</div>');
  assert.equal(p.run('submit',prompt).status,'clicked');
}));
test('origin mismatch reports only origin and not private query or path',()=>withPage(p=>{
  const r=p.run('diagnose');assert.equal(r.status,'origin');
  assert.equal(r.actualOrigin,'https://accounts.example.com');assert.ok(r.expectedHosts.includes('claude.ai'));
  const raw=JSON.stringify(r);assert.ok(!raw.includes('secret'));assert.ok(!raw.includes('/login'));
},'https://accounts.example.com/login?token=secret'));

test('new assistant reply acknowledges delivery even if site retains composer text',()=>withPage(p=>{
  p.w.document.querySelector('#send').onclick=()=>{};
  p.run('prepare');p.run('submit');
  assert.equal(p.run('poll').status,'awaiting_ack');
  p.answer('new reply',true);
  assert.equal(p.run('poll').status,'streaming');
  assert.equal(p.w.document.querySelector('#input').value,'hello');
  p.advance(3100);
  assert.equal(p.run('poll').status,'complete');
}));
test('cleared or missing editor alone never confirms delivery',()=>withPage(p=>{
  p.run('prepare');p.run('submit');
  assert.equal(p.w.document.querySelector('#input').value,'');
  assert.equal(p.run('poll').status,'awaiting_ack');
  p.w.document.querySelector('#input').remove();
  assert.equal(p.run('poll').status,'awaiting_ack');
  p.advance(31000);
  assert.equal(p.run('poll').status,'send_unconfirmed');
  assert.equal(p.clicks(),1);
}));
test('a twelve second stream pause without completion evidence is never completed',()=>withPage(p=>{
  p.run('prepare');p.run('submit');p.answer('partial text');
  assert.equal(p.run('poll').status,'streaming');
  p.advance(12001);assert.equal(p.run('poll').status,'streaming');
  p.advance(120000);assert.equal(p.run('poll').status,'streaming');
}));
