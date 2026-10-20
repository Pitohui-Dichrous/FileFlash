/* Optional browser acceptance checks: npm install --no-save playwright, then node scripts/web-smoke.cjs.
 * Uses an in-process protocol fixture, never a phone. Real-server checks belong to core integration tests.
 */
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const engines = require('playwright');
const engine = process.env.FILEFLASH_BROWSER_ENGINE || 'chromium';
const SHA256 = require('../app/src/main/assets/web/sha256.js');
const root = path.resolve(__dirname, '../app/src/main/assets/web');
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const uploads = new Map(), texts = [], files = [];
let nextId = 0, failAfterFirst = true, expired = false, uploadBytes = 0;
let failCancelOnce = false;
let releaseHeldAck=null;
const wirePuts=[];
const source = crypto.randomBytes(4 * 1024 * 1024 + 17);
const fileName = '长中文文件名 <img src=x onerror=alert(1)> & report.bin';
const text = '中文 English 😀 <script>alert("x")</script>\n第二行 & < >\n' + '较长文字'.repeat(5000);
function publicUpload(t) { const { body, ...metadata } = t; return metadata; }
function json(res, code, body) { res.writeHead(code, {'Content-Type':'application/json'});res.end(JSON.stringify(body)); }
async function body(req) { const chunks=[];for await (const chunk of req)chunks.push(chunk);return Buffer.concat(chunks); }
const server = http.createServer(async (req,res) => {
  const url = new URL(req.url,'http://localhost');
  try {
    if(url.pathname.startsWith('/api/')) {
      if(url.pathname==='/api/pair') { const input=JSON.parse(await body(req));return input.token==='test-pair'?json(res,200,{session:'session',clientId:'browser',chunkSize:2097152}):json(res,401,{error:'配对码无效'}); }
      if(url.pathname==='/api/downloads/test-ticket') { res.writeHead(200,{'Content-Disposition':'attachment; filename="download.bin"','Content-Type':'application/octet-stream','Content-Length':source.length});res.end(source);return; }
      if(req.headers.authorization!=='Bearer session'||expired)return json(res,401,{error:'配对已失效'});
      if(url.pathname==='/api/state')return json(res,200,{serverName:'Android',files,texts,uploads:Array.from(uploads.values()).map(publicUpload),downloads:[]});
      if(url.pathname==='/api/session'&&req.method==='DELETE')return json(res,200,{ok:true});
      if(url.pathname==='/api/texts'&&req.method==='POST') { const input=JSON.parse(await body(req));texts.push({id:'text-'+texts.length,text:input.text,sender:'iPhone',createdAt:Date.now()});return json(res,200,texts.at(-1)); }
      if(/^\/api\/files\/[^/]+\/ticket$/.test(url.pathname))return json(res,200,{url:'/api/downloads/test-ticket',expiresAt:Date.now()+600000});
      if(url.pathname==='/api/uploads'&&req.method==='POST') { const input=JSON.parse(await body(req));const item={...input,id:'upload-'+(++nextId),resumeKey:'key',offset:0,status:'uploading',body:Buffer.alloc(0)};uploads.set(item.id,item);return json(res,200,publicUpload(item)); }
      const match=url.pathname.match(/^\/api\/uploads\/([^/]+)(\/(?:complete|prefix))?$/);
      if(match) {
        const item=uploads.get(match[1]);if(!item)return json(res,404,{error:'任务不存在'});
        assert.equal(req.headers['x-upload-key'],'key');
        if(req.method==='GET'&&match[2]==='/prefix')return json(res,200,{id:item.id,size:item.size,offset:item.offset,prefixSha256:sha(item.body)});
        if(req.method==='GET')return json(res,200,publicUpload(item));
        if(req.method==='DELETE'){if(failCancelOnce){failCancelOnce=false;return json(res,503,{error:'测试取消失败'});}item.status='cancelled';return json(res,200,publicUpload(item));}
        if(req.method==='PUT') {
          const chunk=await body(req),offset=Number(req.headers['x-offset']);assert.ok(chunk.length<=2097152);assert.equal(sha(chunk),req.headers['x-chunk-sha256']);
          if(wirePuts.length<24)wirePuts.push({offset,bodyBytes:chunk.length,headers:Object.fromEntries(['content-length','content-type','transfer-encoding','expect','connection','user-agent'].filter(key=>req.headers[key]!==undefined).map(key=>[key,req.headers[key]]))});
          item.puts=(item.puts||0)+1;
          (item.putLengths||(item.putLengths=[])).push(chunk.length);
          const duplicate=offset===item.lastOffset&&chunk.length===item.lastLength&&item.offset===offset+chunk.length;
          if(duplicate){assert.deepEqual(item.body.subarray(offset,offset+chunk.length),chunk);return json(res,200,publicUpload(item));}
          assert.equal(offset,item.offset);
          if(failAfterFirst&&item.offset>0)return json(res,503,{error:'测试连接中断'});
          if(/^fixture-drop-before(?:-ios)?\.bin$/.test(item.name)&&item.puts===1){res.destroy();return;}
          if(/^fixture-(?:pause|cancel)-recovery\.bin$/.test(item.name))return json(res,503,{error:'测试持续断网'});
          if(item.name==='fixture-chunk-corrupt.bin')return json(res,422,{error:'测试分块校验失败',code:'chunk_checksum'});
          item.lastOffset=offset;item.lastLength=chunk.length;item.body=Buffer.concat([item.body,chunk]);item.offset+=chunk.length;uploadBytes+=chunk.length;
          if(/^fixture-lost-ack(?:-ios)?\.bin$/.test(item.name)&&item.puts===1){res.destroy();return;}
          if(item.name==='fixture-held-ack.bin')await new Promise(resolve=>{releaseHeldAck=resolve;});
          return json(res,200,publicUpload(item));
        }
        if(req.method==='POST'&&match[2]==='/complete'){const input=JSON.parse(await body(req));assert.equal(item.body.length,item.size);if(item.name==='fixture-final-corrupt.bin')item.body[0]^=1;if(sha(item.body)!==input.sha256){item.body=Buffer.alloc(0);item.offset=0;item.lastOffset=-1;item.status='failed';return json(res,422,{error:'测试完整文件校验失败',code:'file_checksum'});}if(item.sha256)assert.equal(item.sha256,input.sha256);item.sha256=input.sha256;item.status='complete';files.push({id:item.id,name:item.name,size:item.size,mime:item.mime,sha256:input.sha256,createdAt:Date.now()});return json(res,200,publicUpload(item));}
      }
      return json(res,404,{error:'not found'});
    }
    const rel=url.pathname==='/'?'index.html':url.pathname.slice(1);
    if(!['index.html','app.js','sha256.js','styles.css','icon.svg'].includes(rel)){res.writeHead(404);res.end();return;}
    const types={html:'text/html; charset=utf-8',js:'text/javascript',css:'text/css',svg:'image/svg+xml'};
    res.writeHead(200,{'Content-Type':types[rel.split('.').pop()],'Cache-Control':'no-store'});fs.createReadStream(path.join(root,rel)).pipe(res);
  }catch(err){json(res,500,{error:err.message});}
});
(async()=>{
  for(const n of [0,1,55,56,63,64,65,127,128,129,1000000,2097153]){const input=crypto.randomBytes(n),hash=new SHA256();for(let i=0;i<n;i+=37)hash.update(input.subarray(i,i+37));assert.equal(hash.clone().hex(),sha(input));assert.equal(hash.finished,false);assert.equal(hash.hex(),sha(input));assert.equal(hash.clone().hex(),sha(input));}
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const origin='http://127.0.0.1:'+server.address().port;
  const browser=await engines[engine].launch(engine==='chromium'?{headless:true,channel:process.env.FILEFLASH_BROWSER_CHANNEL||'chromium'}:{headless:true});
  const context=await browser.newContext({viewport:{width:375,height:812},deviceScaleFactor:1,reducedMotion:'reduce',acceptDownloads:true});
  await context.addInitScript(()=>Object.defineProperty(navigator,'clipboard',{value:undefined,configurable:true}));
  await context.addInitScript(()=>{window.fileReadBytes=0;const read=Blob.prototype.arrayBuffer;Blob.prototype.arrayBuffer=async function(){const data=await read.call(this);window.fileReadBytes+=data.byteLength;return data;};});
  await context.addInitScript(()=>{window.uploadBodyShapes=[];const send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(data){if(window.uploadBodyShapes.length<20)window.uploadBodyShapes.push({type:Object.prototype.toString.call(data),nativeArrayBuffer:data instanceof ArrayBuffer,byteLength:data&&data.byteLength});return send.call(this,data);};});
  const page=await context.newPage();const pageErrors=[];page.on('pageerror',err=>pageErrors.push(err.message));
  let firstSendReadBytes=null;
  await page.route('**/api/uploads/*',async route=>{if(route.request().method()==='PUT'&&firstSendReadBytes===null)firstSendReadBytes=await page.evaluate(()=>window.fileReadBytes);await route.continue();});
  try{
    await page.goto(origin+'/#pair=test-pair');await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor();assert.equal(new URL(page.url()).hash,'');
    await page.locator('#file-input').setInputFiles({name:fileName,mimeType:'application/octet-stream',buffer:source});
    await page.getByText('测试连接中断',{exact:true}).waitFor({timeout:30000});assert.equal(uploads.size,1);assert.equal(Array.from(uploads.values())[0].offset,2097152);assert.ok(firstSendReadBytes<source.length);assert.equal(firstSendReadBytes,2097152);assert.equal(Array.from(uploads.values())[0].sha256,undefined);const bodyShapes=await page.evaluate(()=>window.uploadBodyShapes);assert.ok(bodyShapes.every(value=>value.nativeArrayBuffer&&value.type==='[object ArrayBuffer]'));failAfterFirst=false;
    await page.reload();await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor();await page.locator('[data-tab="tasks"]').click();
    await page.getByRole('button',{name:'选择原文件',exact:true}).click();
    const wrong=Buffer.from(source);wrong[0]^=1;await page.locator('#file-input').setInputFiles({name:fileName,mimeType:'application/octet-stream',buffer:wrong});
    await page.getByText('所选文件与原文件内容不同。请重新选择原文件。',{exact:true}).waitFor();
    await page.getByRole('button',{name:'选择原文件',exact:true}).click();await page.locator('#file-input').setInputFiles({name:fileName,mimeType:'application/octet-stream',buffer:source});
    await page.getByText('已完成',{exact:true}).waitFor({timeout:30000});assert.equal(uploads.size,1);assert.equal(uploadBytes,source.length);assert.deepEqual(Array.from(uploads.values())[0].body,source);
    const completedTask=await page.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1'))[0]);assert.equal(completedTask.resumeKey,undefined);assert.ok(completedTask.finishedAt);assert.equal(await page.locator('#file-input').evaluate(el=>el.files.length),0);
    await page.locator('[data-tab="files"]').click();await page.locator('#file-list').getByText(fileName,{exact:true}).waitFor();assert.equal(await page.locator('img').count(),0);
    const downloadEvent=page.waitForEvent('download');await page.getByRole('button',{name:'下载',exact:true}).click();const download=await downloadEvent;assert.equal(sha(await fs.promises.readFile(await download.path())),sha(source));
    await page.locator('[data-tab="texts"]').click();await page.locator('#text-input').fill(text);await page.getByRole('button',{name:'发送',exact:true}).click();await page.locator('.received-text').waitFor();assert.equal(await page.locator('.received-text').inputValue(),text);assert.equal(await page.locator('script').count(),2);
    await page.getByRole('button',{name:'复制',exact:true}).click();await page.locator('.copy-note').filter({hasText:/已复制|已选中文字/}).waitFor();
    await page.locator('[data-tab="files"]').click();
    const out=path.resolve(__dirname,'web-artifacts',engine);await fs.promises.mkdir(out,{recursive:true});await page.screenshot({path:path.join(out,'mobile-files.png'),fullPage:true});
    for(const viewport of [{width:375,height:812},{width:812,height:375},{width:768,height:1024},{width:1440,height:900}]){
      await page.setViewportSize(viewport);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal overflow '+JSON.stringify(viewport));
      const tooSmall=await page.locator('button:visible').evaluateAll(els=>els.filter(el=>{const r=el.getBoundingClientRect();return r.width<44||r.height<44;}).map(el=>el.textContent));assert.deepEqual(tooSmall,[]);
    }
    await page.setViewportSize({width:375,height:812});await page.locator('[data-tab="tasks"]').click();await page.screenshot({path:path.join(out,'mobile-tasks.png'),fullPage:true});
    // Recovery must distinguish a lost response from bytes that never reached storage.
    const recovery=await browser.newContext({viewport:{width:375,height:812},reducedMotion:'reduce'}),recoveryPage=await recovery.newPage();
    recoveryPage.on('pageerror',err=>pageErrors.push(err.message));
    try{
      await recoveryPage.goto(origin+'/#pair=test-pair');await recoveryPage.locator('#connection-label').filter({hasText:'已连接'}).waitFor();
      const small=crypto.randomBytes(512*1024+7);
      const send=async name=>{await recoveryPage.locator('#file-input').setInputFiles({name,mimeType:'application/octet-stream',buffer:small});return recoveryPage.locator('.task-row').filter({hasText:name});};
      for(const name of ['fixture-lost-ack.bin','fixture-drop-before.bin']){
        const row=await send(name);await row.getByText('已完成',{exact:true}).waitFor();const item=Array.from(uploads.values()).find(t=>t.name===name);assert.equal(item.puts,2);assert.deepEqual(item.body,small);assert.equal(item.sha256,sha(small));assert.equal(item.offset,small.length);
      }
      const held=await send('fixture-held-ack.bin');await held.locator('.task-detail').filter({hasText:'本块已发送，等待确认'}).waitFor();assert.equal(await held.locator('progress').getAttribute('value'),'0');assert.match(await held.locator('.task-detail').innerText(),/接收端已确认 0 B/);assert.equal(Array.from(uploads.values()).find(t=>t.name==='fixture-held-ack.bin').offset,small.length);assert.equal(typeof releaseHeldAck,'function');releaseHeldAck();releaseHeldAck=null;await held.getByText('已完成',{exact:true}).waitFor();
      const badChunk=await send('fixture-chunk-corrupt.bin');await badChunk.getByText('测试分块校验失败',{exact:true}).waitFor();assert.equal(Array.from(uploads.values()).find(t=>t.name==='fixture-chunk-corrupt.bin').puts,1);
      const badFinal=await send('fixture-final-corrupt.bin');await badFinal.getByText('测试完整文件校验失败',{exact:true}).waitFor();assert.equal(Array.from(uploads.values()).find(t=>t.name==='fixture-final-corrupt.bin').offset,0);
      for(const action of ['pause','cancel']){
        const name='fixture-'+action+'-recovery.bin',row=await send(name);await row.locator('.task-detail').filter({hasText:'正在恢复（1/3）'}).waitFor();await row.getByRole('button',{name:action==='pause'?'暂停':'取消',exact:true}).click();await row.getByText(action==='pause'?'已暂停':'已取消',{exact:true}).waitFor();const item=Array.from(uploads.values()).find(t=>t.name===name),puts=item.puts;await recoveryPage.waitForTimeout(1900);assert.equal(item.puts,puts);assert.equal(item.offset,0);if(action==='cancel')assert.equal(item.status,'cancelled');
      }
    }finally{if(releaseHeldAck){releaseHeldAck();releaseHeldAck=null;}await recovery.close();}
    const ios=await browser.newContext({viewport:{width:390,height:844},reducedMotion:'reduce',userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'}),iosPage=await ios.newPage();iosPage.on('pageerror',err=>pageErrors.push(err.message));
    try{
      await iosPage.goto(origin+'/#pair=test-pair');await iosPage.locator('#connection-label').filter({hasText:'已连接'}).waitFor();const iosSource=crypto.randomBytes(512*1024+7),iosPuts=[];
      await iosPage.route('**/api/uploads/**',async route=>{const request=route.request();if(request.method()==='PUT')iosPuts.push(request.postDataBuffer().length);await route.continue();});
      for(const name of ['fixture-lost-ack-ios.bin','fixture-drop-before-ios.bin']){
        await iosPage.locator('#file-input').setInputFiles({name,mimeType:'application/octet-stream',buffer:iosSource});await iosPage.locator('.task-row').filter({hasText:name}).getByText('已完成',{exact:true}).waitFor();const item=Array.from(uploads.values()).find(t=>t.name===name);assert.equal(item.puts,3);assert.deepEqual(item.putLengths,[512*1024,512*1024,7]);assert.deepEqual(item.body,iosSource);assert.equal(item.sha256,sha(iosSource));
      }
      // Chromium may replay a connection-reset request inside the network stack
      // without firing another Playwright route. The fixture above counts wire PUTs.
      assert.ok(iosPuts.length>=4&&iosPuts.length<=6);assert.ok(iosPuts.every(size=>size===512*1024||size===7));
    }finally{await ios.close();}
    // Older tasks declared the original full checksum. A matching prefix with
    // an altered tail must fail and reset, then accept the actual original file.
    const legacy=await browser.newContext({viewport:{width:375,height:812},reducedMotion:'reduce'}),legacyId='fixture-legacy';
    uploads.set(legacyId,{id:legacyId,resumeKey:'key',name:'legacy-original.bin',size:source.length,mime:'application/octet-stream',sha256:sha(source),offset:2097152,status:'uploading',body:Buffer.from(source.subarray(0,2097152))});
    await legacy.addInitScript(data=>{localStorage.setItem('fileflash.session.v1',JSON.stringify({session:'session',clientId:'browser'}));localStorage.setItem('fileflash.tasks.v1',JSON.stringify([data]));},{key:'legacy',id:legacyId,resumeKey:'key',name:'legacy-original.bin',size:source.length,mime:'application/octet-stream',sha256:sha(source),offset:2097152,status:'paused',createdAt:Date.now()});
    const legacyPage=await legacy.newPage();legacyPage.on('pageerror',err=>pageErrors.push(err.message));
    try{
      await legacyPage.goto(origin);await legacyPage.locator('#connection-label').filter({hasText:'已连接'}).waitFor();await legacyPage.locator('[data-tab="tasks"]').click();await legacyPage.getByRole('button',{name:'选择原文件',exact:true}).click();
      const changedTail=Buffer.from(source);changedTail[changedTail.length-1]^=1;await legacyPage.locator('#file-input').setInputFiles({name:'legacy-original.bin',mimeType:'application/octet-stream',buffer:changedTail});await legacyPage.getByText('所选文件与原文件内容不同。请重新选择原文件。',{exact:true}).waitFor();assert.equal(uploads.get(legacyId).offset,0);
      await legacyPage.getByRole('button',{name:'选择原文件',exact:true}).click();await legacyPage.locator('#file-input').setInputFiles({name:'legacy-original.bin',mimeType:'application/octet-stream',buffer:source});await legacyPage.getByText('已完成',{exact:true}).waitFor();assert.deepEqual(uploads.get(legacyId).body,source);assert.equal(uploads.get(legacyId).sha256,sha(source));
    }finally{await legacy.close();}
    // A long-lived tab must cap history without discarding paused work or its continuation credentials.
    const retention=await browser.newContext({viewport:{width:375,height:812},reducedMotion:'reduce'});
    const tiny=Buffer.from('restart at zero'),now=Date.now(),cancelId='fixture-cancel';
    const seeded=Array.from({length:105},(_,i)=>({key:'paused-'+i,id:i===0?'expired-upload':i===1?cancelId:'paused-upload-'+i,resumeKey:'key',name:i===0?'resume-expired.bin':i===1?'cancel-check.bin':'paused-'+i+'.bin',size:i===0?tiny.length:0,mime:'application/octet-stream',sha256:i===0?sha(tiny):undefined,offset:i===0?3:0,status:'paused',createdAt:now-10*86400000}));
    seeded.push({key:'expired-history',name:'expired-history.bin',size:0,status:'complete',createdAt:now-8*86400000,resumeKey:'obsolete'});
    seeded.push(...Array.from({length:120},(_,i)=>({key:'history-'+i,name:'history-'+i+'.bin',size:0,status:i%2?'complete':'cancelled',createdAt:now,resumeKey:'obsolete'})));
    uploads.set(cancelId,{id:cancelId,resumeKey:'key',name:'cancel-check.bin',size:0,offset:0,status:'uploading',body:Buffer.alloc(0)});
    await retention.addInitScript(data=>{localStorage.setItem('fileflash.session.v1',JSON.stringify({session:'session',clientId:'browser'}));localStorage.setItem('fileflash.tasks.v1',JSON.stringify(data));},seeded);
    const historyPage=await retention.newPage();historyPage.on('pageerror',err=>pageErrors.push(err.message));
    try{
      await historyPage.goto(origin);await historyPage.locator('#connection-label').filter({hasText:'已连接'}).waitFor();await historyPage.locator('[data-tab="tasks"]').click();
      let stored=await historyPage.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')));
      assert.equal(stored.filter(t=>t.status==='paused').length,105);assert.equal(stored.filter(t=>['complete','cancelled'].includes(t.status)).length,100);assert.ok(!stored.some(t=>t.key==='expired-history'));assert.ok(stored.filter(t=>['complete','cancelled'].includes(t.status)).every(t=>!t.resumeKey));assert.ok(stored.filter(t=>t.status==='paused').every(t=>t.resumeKey==='key'));assert.equal(await historyPage.locator('.task-row').count(),205);
      await historyPage.locator('.task-row').filter({hasText:'resume-expired.bin'}).getByRole('button',{name:'选择原文件',exact:true}).click();await historyPage.locator('#file-input').setInputFiles({name:'resume-expired.bin',mimeType:'application/octet-stream',buffer:tiny});
      await historyPage.locator('.task-row').filter({hasText:'resume-expired.bin'}).getByText('已完成',{exact:true}).waitFor();await historyPage.locator('#toast').filter({hasText:'断点已清理，将重新发送。'}).waitFor();
      stored=await historyPage.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')));const restarted=stored.find(t=>t.name==='resume-expired.bin');assert.notEqual(restarted.id,'expired-upload');assert.equal(restarted.offset,tiny.length);assert.equal(restarted.resumeKey,undefined);assert.deepEqual(uploads.get(restarted.id).body,tiny);assert.equal(stored.filter(t=>['complete','cancelled'].includes(t.status)).length,100);assert.equal(await historyPage.locator('.task-row').count(),204);
      failCancelOnce=true;const cancelledRow=historyPage.locator('.task-row').filter({hasText:'cancel-check.bin'});await cancelledRow.getByRole('button',{name:'取消',exact:true}).click();await cancelledRow.getByText('未能在 Android 上取消：测试取消失败',{exact:true}).waitFor();
      stored=await historyPage.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')));assert.equal(stored.find(t=>t.id===cancelId).resumeKey,'key');assert.equal(uploads.get(cancelId).status,'uploading');
      await cancelledRow.getByRole('button',{name:'取消',exact:true}).click();await cancelledRow.getByText('已取消',{exact:true}).waitFor();stored=await historyPage.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')));assert.equal(stored.find(t=>t.id===cancelId).resumeKey,undefined);assert.equal(uploads.get(cancelId).status,'cancelled');assert.equal(stored.filter(t=>['complete','cancelled'].includes(t.status)).length,100);assert.equal(await historyPage.locator('.task-row').count(),203);
    }finally{await retention.close();}
    expired=true;await page.locator('#connection-button').click();await page.getByRole('button',{name:'重新连接',exact:true}).click();await page.getByText('配对已失效。请重新扫描 Android 上的二维码。',{exact:true}).waitFor();
    assert.deepEqual(pageErrors,[]);
    const checks=['SHA vectors + non-mutating clone','first PUT after only 2 MiB read, before complete 4 MiB + 17 byte file','initial create without full SHA','native ArrayBuffer request bodies and wire Content-Length/body equality','2 MiB chunk SHA checks','bounded automatic retry exhaustion','reload and same-size wrong-prefix rejection','same-ID prefix resume + final SHA/byte equality','lost ACK after commit: duplicate retransmission without duplicate bytes','connection dropped before commit: automatic retry','simulated iPhone 512 KiB+7 bytes: lost ACK and drop-before-commit both finish in 3 PUTs, no duplicate bytes','ACK-only progress while server response deliberately held','422 chunk checksum error not automatically retried','final file checksum corruption rejected','pause and cancel interrupt automatic recovery','legacy declared full SHA: matching prefix/altered tail rejected, server reset, correct original retry succeeds','native download','Unicode text + clipboard fallback + XSS-as-text','4 responsive widths + 44 px touch targets','expired credentials','100-row/7-day terminal history retention, paused work preserved','completion secret/file-input cleanup','expired breakpoint restarts at zero','confirmed/failed cancellation cleanup'];
    const evidence=path.resolve(__dirname,'../docs/test-evidence/ios-upload-browser-'+engine+'.json');await fs.promises.mkdir(path.dirname(evidence),{recursive:true});await fs.promises.writeFile(evidence,JSON.stringify({passed:true,engine,browserVersion:browser.version(),hostPlatform:process.platform,recordedAt:new Date().toISOString(),scope:'Desktop browser engine against a local Node HTTP protocol fixture. Not physical iOS Safari or Android device evidence.',firstSendReadBytes,fileBytes:source.length,bodyShapes,wirePuts,checks,pageErrors},null,2)+'\n');
    console.log('PASS ('+engine+'): '+checks.join('; ')+'.');console.log('Evidence: '+evidence);
    console.log('Browser fixture checks do not claim Android or iOS device compatibility. Screenshots: '+out);
  }finally{await browser.close();server.close();}
})().catch(err=>{console.error(err);server.close();process.exitCode=1;});
