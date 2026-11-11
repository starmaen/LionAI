// Chromium fixtures only. Requests to arena.ai below are intercepted locally; no live-account test.
const {chromium} = require('playwright');
const fs=require('node:fs');
const assert=require('node:assert/strict');
const source=fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/web-session.js'),'utf8');
const config={id:'arena',hosts:['arena.ai','www.arena.ai'],input:"textarea, [contenteditable='true'][role='textbox'], [contenteditable='true'].ProseMirror",send:"button[data-testid='send-button'], button[aria-label='Send'], button[aria-label='Send message'], button[aria-label='Submit'], button[type='submit']",answer:"[data-message-role='assistant'], [data-role='assistant'], [data-testid='assistant-message']",stop:"button[data-testid='stop-button'], button[aria-label='Stop'], button[aria-label='Stop generating'], button[aria-label='Stop response'], button[aria-label='Stop generation']",complete:"button[aria-label*='Copy'], button[data-testid*='copy'], button[title*='Copy'], [data-state='complete']"};
const results=[];
function script(action,prompt='مرحبا',token='audit') {
 return source.replace('__LION_CONFIG__',JSON.stringify(config)).replace('__LION_TOKEN__',JSON.stringify(token))
 .replace('__LION_ACTION__',JSON.stringify(action)).replace('__LION_PROMPT__',JSON.stringify(prompt)).replace('__LION_INTERACTIVE__','false');
}
const fixture=(editable=false,cover=false)=>`<!doctype html><html><body>
 ${editable?'<div id="editor" contenteditable="true" role="textbox" class="ProseMirror"></div>':'<textarea id="editor"></textarea>'}
 <button id="send" aria-label="Send message" disabled>Send</button><main id="messages"></main>
 ${cover?'<div style="position:fixed;inset:0;background:#181818;z-index:9999;color:white">LionAI chat cover (fixture)</div>':''}
 <script>
 const editor=document.querySelector('#editor'), send=document.querySelector('#send');let state='';window.clicks=0;
 editor.style.cssText='display:block;min-height:90px;width:500px;border:1px solid black';
 editor.addEventListener('input',()=>{state=editor.value===undefined?editor.innerText:editor.value;send.disabled=!state.trim();});
 send.onclick=async()=>{
  window.clicks++;const message=state;
  if(editor.value===undefined)editor.textContent='';else editor.value='';
  state='';send.disabled=true;
  const stop=document.createElement('button');stop.setAttribute('aria-label','Stop generation');stop.textContent='Stop';document.body.append(stop);
  const response=await fetch('/audit-reply',{method:'POST',body:message});const data=await response.json();
  const node=document.createElement('article');node.dataset.testid='assistant-message';node.textContent=data.reply;
  const copy=document.createElement('button');copy.setAttribute('aria-label','Copy');node.append(copy);
  document.querySelector('#messages').append(node);stop.remove();
 };
 </script></body></html>`;
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 async function check(name,fn){try{await fn();results.push({name,status:'PASS'});}catch(e){results.push({name,status:'FAIL',error:String(e.stack||e)});}}
 for(const [editable,covered] of [[false,false],[false,true],[true,false],[true,true]]){
  await check(`controlled ${editable?'contenteditable':'textarea'}, ${covered?'covered':'visible'}, full send/ack/response`,async()=>{
   const context=await browser.newContext({viewport:{width:540,height:1000}});const page=await context.newPage();const sent=[];
   await context.route('https://arena.ai/**',async route=>{
    if(route.request().url().endsWith('/audit-reply')){sent.push(route.request().postData());await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({reply:'الرد الاختباري من صفحة محلية محاكية'})});}
    else await route.fulfill({status:200,contentType:'text/html',body:fixture(editable,covered)});
   });
   try{
    await page.goto('https://arena.ai/text/direct');
    const prompt='مرحبا\nسطر ثانٍ\n\nنص للتأكد من الإدخال';
    const run=async a=>JSON.parse(await page.evaluate(script(a,prompt)));
    assert.equal((await run('probe')).status,'ready');
    const prepared=await run('prepare');
    if(prepared.status!=='prepared')console.log('EDITOR_FAILURE',JSON.stringify(await page.locator('#editor').evaluate(n=>({innerText:n.innerText,textContent:n.textContent,html:n.innerHTML,focused:document.activeElement===n}))),JSON.stringify({expected:prompt}));
    assert.equal(prepared.status,'prepared');
    assert.equal(await page.locator('#send').isDisabled(),false);
    const rendered=await page.locator('#editor').evaluate(n=>n.value===undefined?n.innerText:n.value);
    assert.equal(rendered.replace(/\n{3}/g,'\n\n'),prompt);
    assert.equal((await run('submit')).status,'clicked');await run('submit');
    let reply;
    for(let i=0;i<25;i++){reply=await run('poll');if(reply.status==='complete')break;await page.waitForTimeout(200);}
    assert.equal(reply.status,'complete');assert.equal(reply.text,'الرد الاختباري من صفحة محلية محاكية');
    assert.deepEqual(sent,[rendered]);assert.equal(await page.evaluate('window.clicks'),1);
   }finally{await context.close();}
  });
 }
 await check('about:blank now reports exact origin before document commit',async()=>{
  const p=await browser.newPage();const r=JSON.parse(await p.evaluate(script('diagnose')));
  assert.equal(r.status,'origin');assert.equal(r.actualOrigin,'about:blank');await p.close();
 });
 await check('login redirect now reports an origin distinguishable from a blank page',async()=>{
  const p=await browser.newPage();await p.route('https://accounts.google.com/**',r=>r.fulfill({body:'<!doctype html><h1>Login fixture</h1>',contentType:'text/html'}));
  await p.goto('https://accounts.google.com/');const r=JSON.parse(await p.evaluate(script('diagnose')));
  assert.equal(r.status,'origin');assert.equal(r.actualOrigin,'https://accounts.google.com');await p.close();
 });
 await check('HTTP 403 page has no composer; DOM cannot explain HTTP failure by itself',async()=>{
  const p=await browser.newPage();await p.route('https://arena.ai/**',r=>r.fulfill({status:403,body:'<!doctype html><h1>Access denied fixture</h1>',contentType:'text/html'}));
  const response=await p.goto('https://arena.ai/text/direct');assert.equal(response.status(),403);
  assert.equal(JSON.parse(await p.evaluate(script('probe'))).status,'needs_input');await p.close();
 });
 await check('Arena-like unlabelled type=button send control cannot be found by current adapter',async()=>{
  const p=await browser.newPage();await p.route('https://arena.ai/**',r=>r.fulfill({body:fixture(false,false).replace('aria-label="Send message"','type="button"'),contentType:'text/html'}));
  await p.goto('https://arena.ai/text/direct');assert.equal(JSON.parse(await p.evaluate(script('prepare'))).status,'prepared');
  assert.equal(JSON.parse(await p.evaluate(script('submit'))).status,'needs_send');await p.close();
 });
 await browser.close();
 console.log(JSON.stringify(results,null,2));if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
})();
