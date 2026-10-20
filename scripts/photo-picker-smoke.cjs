/* Photo-picker diagnostics acceptance. Optional private-ready.json {url,pairToken}
 * selects an already running Java/Android server; otherwise a memory HTTP fixture
 * serves the current web assets. Playwright does not exercise iOS PHPicker.
 */
const assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),engines=require('playwright');
const root=path.resolve(__dirname,'..'),web=path.join(root,'app/src/main/assets/web'),engine=process.env.FILEFLASH_BROWSER_ENGINE||'chromium';
const evidenceSuffix=process.env.FILEFLASH_EVIDENCE_SUFFIX||'';assert.match(evidenceSuffix,/^(?:-[A-Za-z0-9_-]{1,40})?$/,'Invalid evidence suffix');
const version=fs.readFileSync(path.join(root,'app/build.gradle.kts'),'utf8').match(/versionName\s*=\s*"([^"]+)"/)[1],sha=data=>crypto.createHash('sha256').update(data).digest('hex');
const assetSha256=Object.fromEntries(fs.readdirSync(web).filter(name=>fs.statSync(path.join(web,name)).isFile()).sort().map(name=>[name,sha(fs.readFileSync(path.join(web,name)))]));
const external=process.argv[2]?JSON.parse(fs.readFileSync(process.argv[2],'utf8')):null,uploads=new Map(),files=[],checks=[],errors=[],wire=[];
const secrets=new Set(external?[external.pairToken,external.url]:[]);
function sanitized(value){let text=String(value);for(const secret of secrets)if(secret)text=text.split(secret).join('[redacted]');return text;}
let sequence=0,server=null;
async function body(req){const parts=[];for await(const chunk of req)parts.push(chunk);return Buffer.concat(parts);}
function json(res,status,value){res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));}
function publicUpload(item){const {body,...value}=item;return value;}
async function fixture(){
  server=http.createServer(async(req,res)=>{try{
    const url=new URL(req.url,'http://localhost');
    if(url.pathname.startsWith('/api/')){
      if(url.pathname==='/api/pair'){const value=JSON.parse(await body(req));return value.token==='picker-secret'?json(res,200,{session:'picker-session-secret',clientId:'browser',chunkSize:2097152}):json(res,401,{error:'配对失败'});}
      if(!url.pathname.startsWith('/api/downloads/')&&req.headers.authorization!=='Bearer picker-session-secret')return json(res,401,{error:'未配对'});
      if(url.pathname==='/api/state')return json(res,200,{serverName:'Android',files,texts:[],uploads:[...uploads.values()].map(publicUpload),downloads:[]});
      if(url.pathname==='/api/diagnostics')return json(res,200,{schema:1,running:true,transport:{capacity:128,requests:[]}});
      if(url.pathname==='/api/diagnostics/probe'){const bytes=await body(req);assert.equal(bytes.length,512*1024);return json(res,200,{length:bytes.length,sha256:sha(bytes)});}
      if(url.pathname==='/api/uploads'&&req.method==='POST'){const value=JSON.parse(await body(req));assert.equal(value.restartOnly,true);assert.equal(value.sha256,undefined);const item={...value,id:'picker-upload-'+(++sequence),resumeKey:'picker-cleanup-secret',offset:0,status:'uploading',body:Buffer.alloc(0)};uploads.set(item.id,item);return json(res,201,publicUpload(item));}
      const match=url.pathname.match(/^\/api\/uploads\/([^/]+)(\/stream)?$/);
      if(match){const item=uploads.get(match[1]);if(!item)return json(res,404,{error:'任务不存在'});assert.equal(req.headers['x-upload-key'],'picker-cleanup-secret');
        if(req.method==='DELETE'){uploads.delete(item.id);const index=files.findIndex(value=>value.id===item.id);if(index>=0)files.splice(index,1);return json(res,200,{status:'cancelled'});}
        if(req.method==='POST'&&match[2]){assert.equal(req.headers['x-chunk-sha256'],undefined);assert.equal(req.headers['x-offset'],undefined);item.body=await body(req);assert.equal(item.body.length,item.size);item.status='complete';item.offset=item.size;item.sha256=sha(item.body);item.temporary=true;files.push({id:item.id,name:item.name,size:item.size,mime:item.mime,temporary:true});return json(res,200,publicUpload(item));}
      }
      const ticket=url.pathname.match(/^\/api\/files\/([^/]+)\/ticket$/);if(ticket&&req.method==='POST')return json(res,200,{url:'/api/downloads/'+ticket[1]});
      const download=url.pathname.match(/^\/api\/downloads\/([^/]+)$/);if(download){const item=uploads.get(download[1]);assert.ok(item);res.writeHead(200,{'Content-Type':'application/octet-stream','Content-Length':item.body.length});res.end(item.body);return;}
      return json(res,404,{error:'not found'});
    }
    const asset=url.pathname==='/'?'index.html':url.pathname.slice(1);if(!Object.hasOwn(assetSha256,asset)){res.writeHead(404);res.end();return;}
    res.writeHead(200,{'Content-Type':({html:'text/html; charset=utf-8',js:'text/javascript',css:'text/css',svg:'image/svg+xml'})[asset.split('.').pop()]||'application/octet-stream','Cache-Control':'no-store'});res.end(fs.readFileSync(path.join(web,asset)));
  }catch(error){if(!res.headersSent)json(res,500,{error:error.message});else res.destroy(error);}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));return {url:'http://127.0.0.1:'+server.address().port,pairToken:'picker-secret'};
}
const iphoneAgent='Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X) AppleWebKit/605.1.15 Version/27.0 Mobile Safari/605.1.15';
const runId=Date.now().toString(36),privateNames=[],selected=[];
function media(ext,mime,size=65553){const bytes=Buffer.alloc(size);for(let i=0;i<bytes.length;i++)bytes[i]=(i*31+17)&255;const name='picker-private-'+runId+'-'+privateNames.length+'.'+ext;privateNames.push(name);return {name,mimeType:mime,buffer:bytes};}
function instrument(){
  window.pickerTest={arrayBufferReads:0,canvasCalls:0,imageBitmapCalls:0,nativeProgrammaticClicks:0,nativeClicks:[],changeSnapshots:[],streams:[]};
  const read=Blob.prototype.arrayBuffer;Blob.prototype.arrayBuffer=function(){window.pickerTest.arrayBufferReads++;return read.apply(this,arguments);};
  for(const key of ['toDataURL','toBlob']){const method=HTMLCanvasElement.prototype[key];HTMLCanvasElement.prototype[key]=function(){window.pickerTest.canvasCalls++;return method.apply(this,arguments);};}
  if(window.createImageBitmap){const method=window.createImageBitmap;window.createImageBitmap=function(){window.pickerTest.imageBitmapCalls++;return method.apply(this,arguments);};}
  const click=HTMLElement.prototype.click;HTMLElement.prototype.click=function(){if(this.id==='native-file-input')window.pickerTest.nativeProgrammaticClicks++;return click.apply(this,arguments);};
  document.addEventListener('click',event=>{if(event.target.id==='native-file-input')window.pickerTest.nativeClicks.push({trusted:event.isTrusted});});
  document.addEventListener('change',event=>{if(['file-input','native-file-input'].includes(event.target.id))window.pickerTest.changeSnapshots.push({input:event.target.id,remainingFiles:event.target.files.length});});
  const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(method,url){this.pickerTestRoute=url;this.pickerTestMethod=method;return open.apply(this,arguments);};
  XMLHttpRequest.prototype.send=function(file){if(this.pickerTestRoute&&this.pickerTestRoute.endsWith('/stream'))window.pickerTest.streams.push({method:this.pickerTestMethod,type:Object.prototype.toString.call(file),bytes:file.size});return send.call(this,file);};
}
(async()=>{
  const ready=external||await fixture(),origin=ready.url.replace(/\/$/,'');
  if(external){assert.equal(external.version,version,'Ready metadata must identify the current version');assert.match(external.serverApkSha256,/^[a-f0-9]{64}$/);assert.equal(sha(fs.readFileSync(path.join(root,'dist/FileFlash-'+version+'.apk'))),external.serverApkSha256,'Ready metadata must match the final local signed APK');}
  const browser=await engines[engine].launch(engine==='chromium'?{headless:true,channel:process.env.FILEFLASH_BROWSER_CHANNEL||'chrome'}:{headless:true});
  const created=new Map(),pendingCreates=new Set(),recordingErrors=[],manifestPath=path.join(root,'.test-data','photo-picker-'+engine+'-'+runId+'-cleanup.json');
  const output=path.join(root,'docs/test-evidence/photo-picker-browser-'+engine+(external?'-real-server':'')+evidenceSuffix+'.json');
  let passed=false,fixtureCleanup={created:0,deleted:0,stateAbsent:false};
  fs.mkdirSync(path.dirname(manifestPath),{recursive:true});
  function writeManifest(){fs.writeFileSync(manifestPath,JSON.stringify({schema:1,version,engine,runId,origin,records:[...created.values()],fixtureCleanup},null,2)+'\n',{mode:0o600});}
  function failedEvidence(){fs.writeFileSync(output,JSON.stringify({passed:false,version,engine,fixtureCleanup,recordedAt:new Date().toISOString()},null,2)+'\n');}
  async function waitForCreates(){while(pendingCreates.size)await Promise.allSettled([...pendingCreates]);}
  async function recordCreate(route){
    const request=route.request();if(request.method()!=='POST'){await route.continue();return;}
    try{
      const response=await route.fetch();
      if(response.status()>=200&&response.status()<300){
        const item=await response.json(),authorization=request.headers().authorization;
        for(const secret of [authorization,authorization&&authorization.replace(/^Bearer /,''),item.resumeKey,item.id])if(typeof secret==='string')secrets.add(secret);
        assert.ok(typeof authorization==='string'&&authorization.startsWith('Bearer '),'Created fixture lacks owner authorization');
        assert.ok(typeof item.id==='string'&&/^[A-Za-z0-9_-]+$/.test(item.id)&&typeof item.resumeKey==='string'&&item.resumeKey.length>0,'Created fixture lacks exact cleanup credentials');
        assert.ok(!created.has(item.id),'Created fixture ID was reused');
        created.set(item.id,{id:item.id,resumeKey:item.resumeKey,authorization,deleted:false});fixtureCleanup.created=created.size;
        // Persist exact identity before the browser receives creation success.
        writeManifest();
      }
      await route.fulfill({response});
    }catch(error){recordingErrors.push(error.name||'Error');try{await route.abort('failed');}catch(_){} }
  }
  async function cleanupFixtures(){
    await waitForCreates();
    for(const item of created.values()){
      if(item.deleted)continue;
      let status=0;
      for(let attempt=0;attempt<4;attempt++){
        const response=await fetch(origin+'/api/uploads/'+encodeURIComponent(item.id),{method:'DELETE',headers:{Authorization:item.authorization,'X-Upload-Key':item.resumeKey},signal:AbortSignal.timeout(15000)});status=response.status;await response.arrayBuffer();
        if(![409,429,503].includes(status)||attempt===3)break;
        await new Promise(resolve=>setTimeout(resolve,100*Math.pow(2,attempt)));
      }
      assert.ok(status>=200&&status<300||status===404||status===410,'Exact fixture cleanup rejected (HTTP '+status+')');
      item.deleted=true;item.deleteStatus=status;fixtureCleanup.deleted=[...created.values()].filter(value=>value.deleted).length;writeManifest();
    }
    const ids=new Set(created.keys());
    for(const authorization of new Set([...created.values()].map(item=>item.authorization))){
      const response=await fetch(origin+'/api/state',{headers:{Authorization:authorization},signal:AbortSignal.timeout(15000)});assert.equal(response.status,200,'Cleanup state verification failed');const state=await response.json();
      assert.ok(Array.isArray(state.files)&&Array.isArray(state.uploads),'Cleanup state is invalid');
      assert.ok(!state.files.some(item=>ids.has(item.id))&&!state.uploads.some(item=>ids.has(item.id)),'Recorded fixture remains in server state');
      assert.ok(!(state.downloads||[]).some(item=>ids.has(item.fileId)&&item.status==='downloading'),'Recorded fixture still has an active download');
    }
    fixtureCleanup.stateAbsent=true;writeManifest();return fixtureCleanup;
  }
  async function context({ios=true,legacy=false}={}){
    const value=await browser.newContext({viewport:{width:390,height:844},reducedMotion:'reduce',acceptDownloads:true,userAgent:ios?iphoneAgent:'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36'});await value.addInitScript(instrument);
    if(legacy)await value.addInitScript(()=>localStorage.setItem('fileflash.tasks.v1',JSON.stringify([{key:'picker-retry',name:'picker-retry-task',size:17,status:'failed',createdAt:Date.now()}])));
    const page=await value.newPage();page.on('pageerror',error=>errors.push(sanitized(error.message)));page.on('request',request=>{if(/\/api\/uploads\/[^/]+\/stream$/.test(request.url()))wire.push({method:request.method(),route:'stream'});});
    await page.route('**/api/uploads',route=>{const pending=recordCreate(route).finally(()=>pendingCreates.delete(pending));pendingCreates.add(pending);return pending;});
    await page.goto(origin+'/#pair='+encodeURIComponent(ready.pairToken));await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor();secrets.add(await page.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.session.v1')).session));return {value,page};
  }
  async function report(page){
    if(!await page.locator('#connection-details').isVisible())await page.locator('#connection-button').click();
    const received=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/diagnostics');await page.locator('#diagnostic-button').click();await received;
    await page.waitForFunction(()=>!document.getElementById('diagnostic-button').disabled);
    const value=JSON.parse(await page.locator('#connection-details textarea[aria-label="传输诊断"]').inputValue());assert.ok(value.picker);return value;
  }
  async function chooser(page,locator,multiple){const chosen=page.waitForEvent('filechooser');await locator.click();const value=await chosen;assert.equal(value.isMultiple(),multiple);return value;}
  async function normal(page,multiple=true){await page.locator('[data-tab="files"]').click();return chooser(page,page.locator('#choose-files'),multiple);}
  async function complete(page,file){
    await page.waitForFunction(name=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')).some(task=>task.name===name&&task.status==='complete'),file.name,{timeout:45000});
    const result=await page.evaluate(async name=>{const session=JSON.parse(localStorage.getItem('fileflash.session.v1')).session,headers={Authorization:'Bearer '+session},state=await(await fetch('/api/state',{headers})).json(),file=state.files.filter(item=>item.name===name).at(-1);if(!file)throw new Error('Uploaded file is missing');const response=await fetch('/api/files/'+encodeURIComponent(file.id)+'/ticket',{method:'POST',headers});if(!response.ok)throw new Error('Download ticket rejected');return(await response.json()).url;},file.name);
    const downloaded=await page.context().request.get(new URL(result,origin).href);assert.equal(downloaded.status(),200);assert.equal(sha(await downloaded.body()),sha(file.buffer));selected.push({bytes:file.buffer.length,sha256:sha(file.buffer)});
  }
  function privateReport(value){const raw=JSON.stringify(value);for(const item of [...privateNames,ready.pairToken,origin,'picker-session-secret','picker-cleanup-secret','image/heic','video/quicktime','application/x-picker-private'])assert.ok(!raw.includes(item),'Diagnostic leaked a private value');assert.ok(!raw.includes('user-cancel'));for(const event of value.picker.events){assert.ok(['open','input','change','cancel'].includes(event.event));assert.ok(['normal','retry','native'].includes(event.source));assert.equal(event.accept,'unrestricted');assert.ok(event.media.every(type=>['heic','heif','jpeg','png','quicktime','mp4','other'].includes(type)));}assert.ok(value.picker.attempts.length<=2);}
  try{
    failedEvidence();writeManifest();
    const main=await context(),page=main.page;
    if(process.env.FILEFLASH_IOS_SHARE_TEST==='1'){
      const media=page.locator('#choose-media'),dialog=page.locator('#media-dialog');
      assert.ok(await media.isVisible());assert.equal(await page.locator('#choose-files-label').textContent(),'文件');
      assert.equal(await dialog.isVisible(),false);await media.click();assert.ok(await dialog.isVisible());
      assert.equal(await dialog.locator('#shortcut-download').getAttribute('href'),'/FileFlash.shortcut');
      assert.equal(await dialog.locator('#shortcut-download').getAttribute('download'),'FileFlash.shortcut');
      assert.deepEqual(await dialog.locator('li').allTextContents(),['照片 → 分享 → 选项 → 当前','FileFlash → 扫安卓二维码']);
      const bounds=await dialog.boundingBox(),viewport=page.viewportSize();assert.ok(bounds.x>=0&&bounds.y>=0&&bounds.x+bounds.width<=viewport.width&&bounds.y+bounds.height<=viewport.height);
      const captures=path.join(root,'.test-data/ios-share-ui');fs.mkdirSync(captures,{recursive:true});await page.screenshot({path:path.join(captures,engine+'-media.png')});
      await dialog.getByRole('button',{name:'关闭',exact:true}).click();assert.equal(await dialog.isVisible(),false);
      assert.ok(await media.evaluate(el=>document.activeElement===el));
      await page.screenshot({path:path.join(captures,engine+'-files.png')});
      checks.push('iPhone has separate media and file controls; the modal installs the bundled shortcut and gives two action-only steps including Current format. Closing restores focus and the modal fits the viewport.');
    }
    assert.equal(await page.locator('#file-input').getAttribute('accept'),null);assert.equal(await page.locator('#file-input').getAttribute('capture'),null);assert.equal(await page.locator('#file-input').evaluate(el=>el.multiple),true);
    await page.locator('#connection-button').click();assert.ok(await page.locator('#picker-test-button').isVisible());assert.equal(await page.locator('#native-file-input').isVisible(),false);checks.push('Normal file input remains unrestricted and multiple; iOS picker test stays collapsed until explicitly opened.');
    const unopened=wire.length,waiting=await normal(page),pending=await report(page);assert.equal(pending.picker.attempts.find(item=>item.source==='normal').status,'pending');assert.equal(wire.length,unopened);assert.equal(await page.getByText('失败',{exact:true}).count(),0);
    await page.evaluate(()=>{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));});const stillPending=await report(page);assert.equal(stillPending.picker.attempts.find(item=>item.source==='normal').status,'pending');assert.equal(wire.length,unopened);await waiting.setFiles([]);
    const empty=await report(page),emptyAttempt=empty.picker.attempts.find(item=>item.source==='normal');assert.ok(['no-files','empty','pending'].includes(emptyAttempt.status));assert.equal(wire.length,unopened);privateReport(empty);checks.push('A real click opens the chooser; no change remains pending after focus/visibility events. Empty selection creates no upload and never asserts a user cancellation or an upload failure.');
    const heic=media('HEIC','image/heic',4*1024*1024+17),mov=media('MOV','video/quicktime',512*1024+7);
    const batch=await normal(page);await batch.setFiles([heic,mov]);assert.equal(await page.locator('#file-input').evaluate(el=>el.files.length),0);await complete(page,heic);await complete(page,mov);
    let current=await report(page);privateReport(current);assert.ok(current.picker.events.some(item=>item.event==='change'&&item.source==='normal'&&item.multiple&&item.count===2&&item.bytes===heic.buffer.length+mov.buffer.length));checks.push('Multi-file HEIC and QuickTime payloads survive synchronous FileList copying and immediate input reset; both original byte sequences download unchanged.');
    const repeated=await normal(page);await repeated.setFiles(heic);await page.waitForFunction(()=>window.pickerTest.streams.length===3);await page.waitForFunction(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')).filter(task=>task.status==='complete').length===3);assert.equal(await page.locator('#file-input').evaluate(el=>el.files.length),0);checks.push('Selecting the same file again triggers a new complete upload after input reset.');
    await page.locator('#picker-test-button').click();const native=page.locator('#native-file-input');assert.ok(await native.isVisible());for(const attr of ['accept','capture','multiple'])assert.equal(await native.getAttribute(attr),null);assert.equal(await native.evaluate(el=>el.multiple),false);
    const nativeFile=media('MOV','video/quicktime',131079),nativeChooser=await chooser(page,native,false);await nativeChooser.setFiles(nativeFile);await complete(page,nativeFile);assert.equal(await native.evaluate(el=>el.files.length),0);
    const counters=await page.evaluate(()=>window.pickerTest);assert.equal(counters.nativeProgrammaticClicks,0);assert.ok(counters.nativeClicks.some(item=>item.trusted));assert.ok(counters.changeSnapshots.every(item=>item.remainingFiles===0));assert.equal(counters.arrayBufferReads,0);assert.equal(counters.canvasCalls,0);assert.equal(counters.imageBitmapCalls,0);assert.ok(counters.streams.every(item=>item.method==='POST'&&item.type==='[object File]'));current=await report(page);privateReport(current);assert.ok(current.picker.events.some(item=>item.event==='change'&&item.source==='native'&&!item.multiple&&item.count===1));checks.push('Visible single native input receives a trusted direct click without HTMLElement.click, accept or capture; original File uploads with no Blob.arrayBuffer, canvas or image decoding.');
    const prior=wire.length,firstAt=current.picker.events[0].atMs;for(let i=0;i<18;i++){const value=await normal(page);await value.setFiles([]);}const bounded=await report(page);assert.equal(bounded.picker.capacity,32);assert.equal(bounded.picker.events.length,32);assert.ok(bounded.picker.events[0].atMs>firstAt);assert.equal(wire.length,prior);privateReport(bounded);checks.push('Picker diagnostics retain only the latest 32 RAM events and at most two attempts; no filename, token, URL or raw MIME is included. Repeated empty selections send no file request.');
    const android=await context({ios:false});try{await android.page.locator('#connection-button').click();assert.equal(await android.page.locator('#picker-test-button').isVisible(),false);assert.equal(await android.page.locator('#native-file-input').isVisible(),false);assert.equal(await android.page.locator('#file-input').evaluate(el=>el.multiple),true);if(process.env.FILEFLASH_IOS_SHARE_TEST==='1'){assert.equal(await android.page.locator('#choose-media').isVisible(),false);assert.equal(await android.page.locator('#choose-files-label').textContent(),'选择文件');}}finally{await android.value.close();}checks.push('Android retains the normal multiple-file picker and has no visible iOS test control.');
    const retry=await context({legacy:true});try{const p=retry.page;await p.locator('[data-tab="tasks"]').click();const retryChooser=await chooser(p,p.locator('.task-row').filter({hasText:'picker-retry-task'}).getByRole('button',{name:'选择文件',exact:true}),false),retryPending=await report(p);assert.ok(retryPending.picker.attempts.some(item=>item.source==='retry'&&!item.multiple&&item.status==='pending'));const file=media('HEIC','image/heic',65541);await retryChooser.setFiles(file);await complete(p,file);const newBatch=await normal(p);const a=media('bin','application/x-picker-private',3001),b=media('MOV','video/quicktime',4001);await newBatch.setFiles([a,b]);await complete(p,a);await complete(p,b);const summary=await report(p);privateReport(summary);assert.ok(summary.picker.events.some(item=>item.event==='change'&&item.source==='retry'&&!item.multiple&&item.count===1));assert.ok(summary.picker.events.some(item=>item.event==='change'&&item.source==='normal'&&item.multiple&&item.count===2));const stats=await p.evaluate(()=>window.pickerTest);assert.equal(stats.arrayBufferReads,0);assert.equal(stats.canvasCalls,0);}finally{await retry.value.close();}checks.push('A stored failed task opens the single-file retry picker, then a new upload restores multiple selection and completes both files.');
    assert.deepEqual(errors,[]);await waitForCreates();assert.equal(recordingErrors.length,0,'Fixture creation recording failed');assert.equal(created.size,7,'Expected seven exact fixture identities');await cleanupFixtures();
    const evidence={passed:true,version,engine,browserVersion:browser.version(),assetSha256,...(external?{serverVersion:external.version,serverApkSha256:external.serverApkSha256}:{}),server:external?'existing Java or Android server':'Node memory HTTP fixture',scope:'Desktop browser automation with iPhone identification; real DOM activation, injected FileList and byte-preserving uploads.',limitations:['Playwright supplies synthetic files and bypasses iOS PHPicker, Photos export, HEIC/HEVC decoding and photo-library conversion.','This does not reproduce or prove a fix for the physical iPhone white-circle problem.','Synthetic media bytes are transfer fixtures, not playable camera media.'],selectedPayloads:selected,streamRequests:wire.length,fixtureCleanup,checks,pageErrors:errors,recordedAt:new Date().toISOString()};
    fs.writeFileSync(output,JSON.stringify(evidence,null,2)+'\n');passed=true;console.log('PASS '+engine+': '+checks.length+' picker checks; '+wire.length+' stream requests; '+fixtureCleanup.deleted+'/'+fixtureCleanup.created+' fixtures cleaned. '+output);
    await main.value.close();
  }finally{
    if(!passed){await browser.close();try{await cleanupFixtures();}finally{failedEvidence();}}
    await browser.close();if(server){server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
  }
})().catch(error=>{console.error(sanitized(error.stack||error.message||error));if(server){server.closeAllConnections();server.close();}process.exitCode=1;});
