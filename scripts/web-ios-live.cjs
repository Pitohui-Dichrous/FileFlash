/* Desktop WebKit with iPhone/iPad identification. Does not claim physical iOS. */
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),{webkit}=require('playwright');
const ready=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),sha=data=>crypto.createHash('sha256').update(data).digest('hex');
(async()=>{
  const browser=await webkit.launch({headless:true}),results=[],errors=[];
  try{
    for(const ipad of [false,true]){
      const context=await browser.newContext({viewport:{width:ipad?820:390,height:ipad?1180:844},acceptDownloads:true,reducedMotion:'reduce',...(ipad?{}:{userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X) AppleWebKit/605.1.15 Version/27.0 Mobile Safari/605.1.15'})});
      await context.addInitScript(ipad=>{if(ipad){Object.defineProperty(navigator,'platform',{value:'MacIntel'});Object.defineProperty(navigator,'maxTouchPoints',{value:5});}window.fileReads=0;window.bodies=[];const read=Blob.prototype.arrayBuffer;Blob.prototype.arrayBuffer=function(){window.fileReads++;return read.apply(this,arguments);};const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(method,url){this.testRoute=url;return open.apply(this,arguments);};XMLHttpRequest.prototype.send=function(file){if(this.testRoute.endsWith('/stream'))window.bodies.push({type:Object.prototype.toString.call(file),bytes:file.size,overallTimeoutMs:this.timeout});return send.call(this,file);};},ipad);
      const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
      try{
        await page.goto(ready.url+'/#pair='+encodeURIComponent(ready.pairToken));await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor();
        const source=crypto.randomBytes(4*1024*1024+173),name='single-file-'+Date.now()+'.mov',requests=[];page.on('request',request=>{if(request.url().endsWith('/stream'))requests.push({method:request.method()});});
        await page.locator('#file-input').setInputFiles({name,mimeType:'video/quicktime',buffer:source});await page.getByText('已完成',{exact:true}).waitFor({timeout:45000});const bodies=await page.evaluate(()=>window.bodies);assert.deepEqual(bodies,[{type:'[object File]',bytes:source.length,overallTimeoutMs:0}]);assert.equal(await page.evaluate(()=>window.fileReads),0);assert.deepEqual(requests,[{method:'POST'}]);assert.equal(await page.getByRole('button',{name:'暂停',exact:true}).count(),0);
        await page.locator('[data-tab="files"]').click();const row=page.locator('.file-row').filter({hasText:name});await row.waitFor();assert.match(await row.innerText(),/临时中转/);const downloading=page.waitForEvent('download');await row.getByRole('button',{name:'下载',exact:true}).click();assert.equal(sha(await fs.promises.readFile(await(await downloading).path())),sha(source));assert.equal(await page.locator('#file-input').evaluate(el=>el.files.length),0);
        results.push({identification:ipad?'iPad desktop identification':'iPhone iOS27 identification',fileBytes:source.length,streamRequests:requests.length,requestMethod:'POST',browserFileReads:0,bodies,downloadByteEquality:true,passed:true});
      }finally{await context.close();}
    }
    assert.deepEqual(errors,[]);const version=fs.readFileSync(path.resolve(__dirname,'../app/build.gradle.kts'),'utf8').match(/versionName\s*=\s*"([^"]+)"/)[1],evidence={passed:true,version,engine:'webkit',browserVersion:browser.version(),scope:'Desktop WebKit with simulated iPhone/iPad identification against actual Java core, not physical iOS networking or file pickers.',recordedAt:new Date().toISOString(),results,pageErrors:errors};const output=path.resolve(__dirname,'../docs/test-evidence/stream-ios-live-webkit.json');fs.writeFileSync(output,JSON.stringify(evidence,null,2)+'\n');console.log('PASS WebKit actual core: iPhone/iPad identification, one native File POST each, zero JS file reads, exact downloaded bytes. '+output);
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
