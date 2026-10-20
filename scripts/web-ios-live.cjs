/* iOS upload-path acceptance against the real Java core.
 * The browser runs on desktop with simulated iPhone/iPad identification. This
 * verifies our compatibility path, not physical iOS networking or file pickers.
 * Usage: node scripts/web-ios-live.cjs path/to/private-ready.json
 */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {webkit}=require('playwright');
const ready=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
const origin=ready.url,results=[];
const iphone='Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';
async function api(method,url,session,data,headers={}){
  const opts={method,headers:{...(session?{Authorization:'Bearer '+session}:{}),...headers}};
  if(data!==undefined){if(Buffer.isBuffer(data)){opts.body=data;opts.headers['Content-Type']='application/octet-stream';}else{opts.body=JSON.stringify(data);opts.headers['Content-Type']='application/json';}}
  const response=await fetch(origin+url,opts),result=await response.json();assert.ok(response.ok,method+' '+url.replace(/\/uploads\/[^/]+/,'/uploads/[id]')+' HTTP '+response.status);return result;
}
async function instrument(context,ipad){
  await context.addInitScript(desktopIpad=>{
    if(desktopIpad){Object.defineProperty(navigator,'platform',{value:'MacIntel'});Object.defineProperty(navigator,'maxTouchPoints',{value:5});}
    window.fileReadBytes=0;window.uploadBodyShapes=[];
    const read=Blob.prototype.arrayBuffer;Blob.prototype.arrayBuffer=async function(){const bytes=await read.call(this);window.fileReadBytes+=bytes.byteLength;return bytes;};
    const send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(data){window.uploadBodyShapes.push({nativeArrayBuffer:data instanceof ArrayBuffer,type:Object.prototype.toString.call(data),byteLength:data.byteLength});return send.call(this,data);};
  },ipad);
}
(async()=>{
  const browser=await webkit.launch({headless:true});
  try{
    for(const ipad of [false,true]){
      const context=await browser.newContext({viewport:{width:ipad?820:390,height:ipad?1180:844},reducedMotion:'reduce',acceptDownloads:true,...(ipad?{}:{userAgent:iphone})});
      await instrument(context,ipad);const page=await context.newPage(),errors=[],puts=[];let firstReadBytes;
      page.on('pageerror',err=>errors.push(err.message));
      await page.route('**/api/uploads/**',async route=>{const req=route.request();if(req.method()==='PUT'){if(firstReadBytes===undefined)firstReadBytes=await page.evaluate(()=>window.fileReadBytes);puts.push({offset:Number(req.headers()['x-offset']),bodyBytes:req.postDataBuffer().length});}await route.continue();});
      try{
        await page.goto(origin+'/#pair='+encodeURIComponent(ready.pairToken));await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor();
        const source=crypto.randomBytes((ipad?1:4)*1024*1024+173),name='iOS-path-'+(ipad?'ipad-desktop':'iphone')+'-'+Date.now()+'.apk';
        await page.locator('#file-input').setInputFiles({name,mimeType:'application/vnd.android.package-archive',buffer:source});await page.getByText('已完成',{exact:true}).waitFor({timeout:45000});
        assert.ok(puts.length>2);assert.equal(puts[0].bodyBytes,512*1024);assert.ok(puts.every(p=>p.bodyBytes<=512*1024));assert.equal(firstReadBytes,512*1024);assert.ok(firstReadBytes<source.length);
        const task=await page.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1'))[0]);assert.equal(task.sha256,sha(source));assert.equal(task.offset,source.length);assert.equal(task.resumeKey,undefined);
        const shapes=await page.evaluate(()=>window.uploadBodyShapes);assert.ok(shapes.every(s=>s.nativeArrayBuffer&&s.type==='[object ArrayBuffer]'&&s.byteLength<=512*1024));
        await page.locator('[data-tab="files"]').click();const fileRow=page.locator('.file-row').filter({hasText:name});await fileRow.waitFor();const downloaded=page.waitForEvent('download');await fileRow.getByRole('button',{name:'下载',exact:true}).click();assert.equal(sha(await fs.promises.readFile(await (await downloaded).path())),sha(source));
        assert.equal(await page.locator('#file-input').evaluate(el=>el.files.length),0);assert.deepEqual(errors,[]);
        results.push({identification:ipad?'iPad desktop UA + MacIntel + 5 touch points':'iPhone Safari UA',fileBytes:source.length,firstSendReadBytes:firstReadBytes,puts,nativeArrayBufferBodies:shapes,passed:true,checks:['first request is 512 KiB before whole file is read','multiple small PUTs accepted by real Java core','final SHA equals selected source','download bytes equal selected source','File input and resume secret released after success']});
      }finally{await context.close();}
    }
    // Simulate a task created by the old 2 MiB sender. A new iPhone page must
    // verify that prefix and continue in 512 KiB pieces without a new task ID.
    const source=crypto.randomBytes(4*1024*1024+173),session=await api('POST','/api/pair',null,{token:ready.pairToken,name:'Legacy iOS browser fixture'});
    const upload=await api('POST','/api/uploads',session.session,{name:'old-2MiB-breakpoint-'+Date.now()+'.apk',size:source.length,mime:'application/vnd.android.package-archive',sha256:sha(source)});
    const prefix=source.subarray(0,2*1024*1024),headers={'X-Upload-Key':upload.resumeKey};
    const committed=await api('PUT','/api/uploads/'+upload.id,session.session,prefix,{...headers,'X-Offset':'0','X-Chunk-SHA256':sha(prefix)});assert.equal(committed.offset,prefix.length);
    const context=await browser.newContext({viewport:{width:390,height:844},userAgent:iphone,reducedMotion:'reduce'});await instrument(context,false);
    await context.addInitScript(({session,task})=>{localStorage.setItem('fileflash.session.v1',JSON.stringify(session));localStorage.setItem('fileflash.tasks.v1',JSON.stringify([task]));},{session:{session:session.session,clientId:session.clientId},task:{key:'old-2MiB',id:upload.id,resumeKey:upload.resumeKey,name:upload.name,size:source.length,mime:upload.mime,sha256:sha(source),offset:prefix.length,status:'paused',createdAt:Date.now()}});
    const page=await context.newPage(),puts=[],errors=[];let prefixRequests=0;page.on('pageerror',err=>errors.push(err.message));
    await page.route('**/api/uploads/**',async route=>{const req=route.request();if(req.method()==='GET'&&req.url().endsWith('/prefix'))prefixRequests++;if(req.method()==='PUT')puts.push({offset:Number(req.headers()['x-offset']),bodyBytes:req.postDataBuffer().length});await route.continue();});
    try{
      await page.goto(origin);await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor();await page.locator('[data-tab="tasks"]').click();await page.getByRole('button',{name:'选择原文件',exact:true}).click();await page.locator('#file-input').setInputFiles({name:upload.name,mimeType:upload.mime,buffer:source});await page.getByText('已完成',{exact:true}).waitFor({timeout:45000});
      const task=await page.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1'))[0]);assert.equal(task.id,upload.id);assert.equal(task.sha256,sha(source));assert.equal(task.offset,source.length);assert.equal(prefixRequests,1);assert.equal(puts[0].offset,2*1024*1024);assert.equal(puts[0].bodyBytes,512*1024);assert.ok(puts.every(p=>p.bodyBytes<=512*1024));assert.deepEqual(errors,[]);
      const state=await api('GET','/api/uploads/'+upload.id,session.session,undefined,headers);assert.equal(state.status,'complete');assert.equal(state.sha256,sha(source));
      const response=await fetch(origin+'/api/files/'+upload.id,{headers:{Authorization:'Bearer '+session.session}});assert.equal(response.status,200);assert.equal(sha(Buffer.from(await response.arrayBuffer())),sha(source));
      results.push({identification:'iPhone Safari UA resuming old 2 MiB checkpoint',fileBytes:source.length,seededOffset:prefix.length,prefixRequests,puts,passed:true,checks:['original task ID retained','original 2 MiB prefix verified','all resumed PUTs use at most 512 KiB','completed file hash and actual downloaded bytes equal selected source']});
    }finally{await context.close();}
    const evidence=path.resolve(__dirname,'../docs/test-evidence/ios-upload-live-webkit.json');await fs.promises.writeFile(evidence,JSON.stringify({passed:true,engine:'webkit',browserVersion:browser.version(),hostPlatform:process.platform,recordedAt:new Date().toISOString(),scope:'Desktop WebKit with simulated iPhone and iPad browser identification, against the real Java core. Not a physical iPhone/iPad or actual iOS file-picker/network test.',results},null,2)+'\n');console.log('PASS: real Java core, simulated iPhone and desktop-UA iPad use 512 KiB requests; streaming SHA, exact file download and old 2 MiB same-ID prefix resume. Evidence: '+evidence);
  }finally{await browser.close();}
})().catch(err=>{console.error(err);process.exitCode=1;});
