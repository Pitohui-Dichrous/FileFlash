/* Browser upload to the signed Android APK. Uses only a private ready file.
 * node scripts/web-upload-regression.cjs [ready.json] [source.apk]
 * Faults: six idle sockets and one lost acknowledgment after a committed block.
 */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto');
const engines=require('playwright'),engine=process.env.FILEFLASH_BROWSER_ENGINE||'webkit';
const root=path.resolve(__dirname,'..');
const version=fs.readFileSync(path.join(root,'app/build.gradle.kts'),'utf8').match(/versionName\s*=\s*"([^"]+)"/)[1];
const ready=JSON.parse(fs.readFileSync(process.argv[2]||path.join(root,'.test-data/upload-android-ready.json'),'utf8'));
const file=path.resolve(process.argv[3]||path.join(root,'dist/FileFlash-0.1.1.apk'));
const bytes=fs.readFileSync(file),sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const expected=sha(bytes),name='iOS-upload-regression-'+Date.now()+'.apk';
const address=new URL(ready.url),sockets=new Set();let keepIdle=false,idleOpened=0;
function openIdle(){
  if(!keepIdle)return;
  const socket=net.connect(Number(address.port)||80,address.hostname);sockets.add(socket);idleOpened++;
  socket.on('error',()=>{});socket.on('data',()=>{});
  socket.on('close',()=>{sockets.delete(socket);if(keepIdle)setTimeout(openIdle,20);});
}
(async()=>{
  const browser=await engines[engine].launch(engine==='chromium'?{headless:true,channel:process.env.FILEFLASH_BROWSER_CHANNEL||'chrome'}:{headless:true});
  let lostAck=false,committedOffset=null,firstPutRead=null,initialSha=null;const errors=[],headers=[];
  const context=await browser.newContext({viewport:{width:390,height:844},acceptDownloads:true,reducedMotion:'reduce',isMobile:true,hasTouch:true,userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'});
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
    window.__fileflashReadBytes=0;
    const original=Blob.prototype.arrayBuffer;
    Blob.prototype.arrayBuffer=function(){window.__fileflashReadBytes+=this.size;return original.call(this);};
  });
  await page.route('**/api/uploads**',async route=>{
    const request=route.request();
    if(request.method()==='POST'&&new URL(request.url()).pathname==='/api/uploads')initialSha=JSON.parse(request.postData()).sha256||null;
    if(request.method()==='PUT'){
      if(firstPutRead===null)firstPutRead=await page.evaluate(()=>window.__fileflashReadBytes);
      const h=await request.allHeaders(),bodyBytes=request.postDataBuffer().length;
      headers.push({offset:Number(h['x-offset']),bodyBytes,contentLength:h['content-length']||null,expect:h.expect||null,transferEncoding:h['transfer-encoding']||null});
      if(Number(h['x-offset'])===2097152&&!lostAck){
        lostAck=true;const response=await route.fetch();assert.equal(response.status(),200);
        committedOffset=(await response.json()).offset;assert.equal(committedOffset,2097152+bodyBytes);
        await route.abort('connectionfailed');return;
      }
    }
    await route.continue();
  });
  try{
    await page.goto(ready.url+'/#pair='+encodeURIComponent(ready.pairToken));
    await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor({timeout:30000});
    keepIdle=true;for(let i=0;i<6;i++)openIdle();
    await page.locator('#file-input').setInputFiles({name,mimeType:'application/vnd.android.package-archive',buffer:bytes});
    await page.locator('.task-state').filter({hasText:'已完成'}).waitFor({timeout:180000});
    const task=await page.evaluate(n=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')).find(t=>t.name===n),name);
    assert.equal(task.status,'complete');assert.equal(task.offset,bytes.length);assert.equal(task.sha256,expected);
    assert.ok(lostAck);assert.equal(firstPutRead,512*1024);assert.ok(firstPutRead<bytes.length);assert.equal(initialSha,null);
    assert.ok(headers.length>4&&headers.every(h=>h.bodyBytes>0&&h.bodyBytes<=512*1024));
    // A separate browser context represents C; B's upload is shared without a host save.
    const receiver=await browser.newContext({viewport:{width:390,height:844},acceptDownloads:true});
    const receiverPage=await receiver.newPage();receiverPage.on('pageerror',e=>errors.push(e.message));
    await receiverPage.goto(ready.url+'/#pair='+encodeURIComponent(ready.pairToken));
    await receiverPage.locator('#connection-label').filter({hasText:'已连接'}).waitFor();
    const row=receiverPage.locator('.file-row').filter({hasText:name});await row.waitFor();
    if(version!=='0.1.2')assert.match(await row.innerText(),/临时中转/);
    const downloadEvent=receiverPage.waitForEvent('download');await row.getByRole('button',{name:'下载',exact:true}).click();
    const download=await downloadEvent;assert.equal(sha(await fs.promises.readFile(await download.path())),expected);
    assert.deepEqual(errors,[]);
    const report={passed:true,version,engine,environment:'Desktop browser with iPhone UA/touch emulation against signed Android API34 APK via adb forwarding; not a physical iPhone/Wi-Fi test',serverApkSha256:sha(fs.readFileSync(path.join(root,'dist/FileFlash-'+version+'.apk'))),bytes:bytes.length,sha256:expected,initialWholeFileHash:false,bytesReadBeforeFirstPut:firstPutRead,simultaneousIdleSockets:6,idleConnectionsOpened:idleOpened,lostAckAfterCommittedOffset:committedOffset,recoveredWithoutManualRetry:true,separateReceiverBrowser:true,downloadByteEquality:true,pageErrors:errors,requestHeaders:headers};
    fs.writeFileSync(path.join(root,'docs/test-evidence/'+(version==='0.1.2'?'upload':'relay')+'-'+engine+'-android.json'),JSON.stringify(report,null,2)+'\n');
    console.log('PASS '+engine+': signed Android APK, '+bytes.length+' bytes, first PUT after '+firstPutRead+' bytes read; six idle sockets; lost ACK after offset '+committedOffset+' recovered automatically; final SHA-256 and browser download match.');
  }catch(error){
    console.error('Upload states:',await page.locator('.task-state,.task-error,.task-progress').allTextContents());throw error;
  }finally{
    keepIdle=false;for(const socket of sockets)socket.destroy();await browser.close();
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
