/* Connected only by the guarded dedicated-emulator Python harness.
 * node scripts/transfer-diagnostics-webview.cjs private-ready.json report.json CDP_URL
 * The chooser supplies a synthetic 60 MiB content URI; no personal files.
 */
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const ready=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const reportPath=path.resolve(process.argv[3]),endpoint=process.argv[4]||'http://127.0.0.1:9222';
const round=n=>Math.round(n*100)/100;
const expected=crypto.createHash('sha256');
const block=Buffer.alloc(64*1024);for(let i=0;i<block.length;i++)block[i]=i&255;
for(let i=0;i<960;i++)expected.update(block);
const sha256=expected.digest('hex');

async function connectPage(){
  const targets=await(await fetch(endpoint+'/json/list')).json(),target=targets.find(item=>item.type==='page'&&new URL(item.url).origin===new URL(ready.url).origin);
  assert.ok(target,'Only the separate local test page is selected.');
  const socket=new WebSocket(target.webSocketDebuggerUrl),pending=new Map(),errors=[];let sequence=0;
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',()=>reject(new Error('Test WebView debugger is unavailable.')),{once:true});});
  socket.addEventListener('message',event=>{
    const item=JSON.parse(event.data);
    if(item.method==='Runtime.exceptionThrown')errors.push(item.params.exceptionDetails.text);
    const request=pending.get(item.id);if(!request)return;pending.delete(item.id);clearTimeout(request.timer);
    if(item.error)request.reject(new Error(item.error.message));else request.resolve(item.result);
  });
  const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(new Error('Test WebView command timed out.'));},30000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method,params}));});
  await send('Runtime.enable');
  return {errors,version:(await(await fetch(endpoint+'/json/version')).json()).Browser,
    evaluate:async(fn,arg)=>{const result=await send('Runtime.evaluate',{expression:'('+fn.toString()+')('+JSON.stringify(arg===undefined?null:arg)+')',awaitPromise:true,returnByValue:true,userGesture:true});if(result.exceptionDetails)throw new Error(result.exceptionDetails.text);return result.result.value;},
    close:()=>socket.close()};
}
async function waitFor(page,condition,argument,timeout=30000){const deadline=Date.now()+timeout;while(Date.now()<deadline){if(await page.evaluate(condition,argument))return;await new Promise(resolve=>setTimeout(resolve,100));}throw new Error('Synthetic WebView upload did not complete within its time bound.');}

(async()=>{
  const page=await connectPage();
  try{
    await waitFor(page,()=>document.querySelector('#connection-label')?.textContent==='已连接');
    const previous=await page.evaluate(()=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')||'[]').map(task=>task.key));
    await page.evaluate(()=>{
      window.controlledTransferMetrics={reads:[],requests:[],hashes:[],storage:[],completionResponseAt:null};
      const read=Blob.prototype.arrayBuffer;
      Blob.prototype.arrayBuffer=async function(){const start=performance.now(),result=await read.call(this);window.controlledTransferMetrics.reads.push({bytes:this.size,start,end:performance.now()});return result;};
      const store=Storage.prototype.setItem;
      Storage.prototype.setItem=function(){const start=performance.now();try{return store.apply(this,arguments);}finally{window.controlledTransferMetrics.storage.push({start,end:performance.now()});}};
      const originalFetch=window.fetch;
      window.fetch=async function(input,options){const response=await originalFetch.apply(this,arguments);if(String(input).endsWith('/complete')&&options?.method==='POST'){await response.clone().arrayBuffer();window.controlledTransferMetrics.completionResponseAt=performance.now();}return response;};
      const update=window.FileFlashSHA256.prototype.update;
      window.FileFlashSHA256.prototype.update=function(data){const start=performance.now();try{return update.apply(this,arguments);}finally{window.controlledTransferMetrics.hashes.push({bytes:data.byteLength,start,end:performance.now()});}};
      const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send,setHeader=XMLHttpRequest.prototype.setRequestHeader;
      XMLHttpRequest.prototype.open=function(method,url){this.__controlledMethod=method;this.__controlledUrl=String(url);this.__controlledHeaders={};return open.apply(this,arguments);};
      XMLHttpRequest.prototype.setRequestHeader=function(name,value){if(['x-request-id','x-offset'].includes(name.toLowerCase()))this.__controlledHeaders[name.toLowerCase()]=value;return setHeader.apply(this,arguments);};
      XMLHttpRequest.prototype.send=function(data){
        if(['PUT','POST'].includes(this.__controlledMethod)&&/^\/api\/uploads\/[^/]+(?:\/(?:chunk|stream))?$/.test(this.__controlledUrl)){
          const item={method:this.__controlledMethod,bodyType:Object.prototype.toString.call(data),bodyBytes:typeof data.byteLength==='number'?data.byteLength:data.size,start:performance.now(),uploaded:null,ack:null,status:null,requestId:this.__controlledHeaders['x-request-id']||null,offset:Number(this.__controlledHeaders['x-offset'])};window.controlledTransferMetrics.requests.push(item);
          this.upload.addEventListener('load',()=>{item.uploaded=performance.now();});
          this.addEventListener('loadend',()=>{item.ack=performance.now();item.status=this.status;try{const response=JSON.parse(this.responseText);item.sha256=response.sha256||null;item.confirmedBytes=response.offset;}catch(_){}});
        }return send.apply(this,arguments);
      };
    });
    await page.evaluate(()=>{document.querySelector('[data-tab="files"]').click();document.querySelector('#choose-files').click();});
    await waitFor(page,previous=>{
      const list=JSON.parse(localStorage.getItem('fileflash.tasks.v1')||'[]');
      return list.some(task=>!previous.includes(task.key)&&task.name.startsWith('controlled-android-webview-60MiB')&&task.status==='complete'&&task.offset===60*1024*1024);
    },previous,180000);
    const metrics=await page.evaluate(()=>window.controlledTransferMetrics),task=await page.evaluate(previous=>{const task=JSON.parse(localStorage.getItem('fileflash.tasks.v1')).find(task=>!previous.includes(task.key)&&task.name.startsWith('controlled-android-webview-60MiB'));return{size:task.size,sha256:task.sha256,offset:task.offset};},previous);
    assert.equal(task.size,60*1024*1024);assert.equal(task.sha256||metrics.requests.at(-1).sha256,sha256);assert.equal(task.offset,task.size);
    assert.equal(metrics.requests.reduce((total,request)=>total+request.bodyBytes,0),task.size);
    assert.ok(metrics.requests.every(request=>request.status===200));assert.deepEqual(page.errors,[]);
    let previousAck=metrics.requests[0].start;
    const puts=metrics.requests.map(request=>{
      const result={method:request.method,bodyType:request.bodyType,requestId:request.requestId,offset:request.offset,bodyBytes:request.bodyBytes,betweenPreviousAckAndSendMs:round(request.start-previousAck),uploadEventMs:request.uploaded===null?null:round(request.uploaded-request.start),requestMs:round(request.ack-request.start),ackGapMs:round(request.ack-previousAck)};previousAck=request.ack;return result;
    });
    const version=await page.evaluate(()=>({userAgent:navigator.userAgent,hardwareConcurrency:navigator.hardwareConcurrency}));
    const report={passed:true,recordedAt:new Date().toISOString(),scope:'Actual Android system WebView in a separate test app, on a dedicated emulator, uploading a seekable synthetic content URI to the signed FileFlash APK in the same emulator. Does not verify physical phones, external file providers, iOS or Wi-Fi.',browserVersion:page.version,...version,fileBytes:task.size,sha256,uploadRequestSpanMs:round(metrics.requests.at(-1).ack-metrics.requests[0].start),readMs:round(metrics.reads.reduce((sum,item)=>sum+item.end-item.start,0)),mainThreadHashMs:round(metrics.hashes.reduce((sum,item)=>sum+item.end-item.start,0)),mainThreadHashCalls:metrics.hashes.length,localStorageMs:round(metrics.storage.reduce((sum,item)=>sum+item.end-item.start,0)),pageErrors:page.errors,puts};
    const completionAt=metrics.completionResponseAt||metrics.requests.at(-1).ack;
    report.transferToCompletionMs=round(completionAt-(metrics.reads[0]?.start||metrics.requests[0].start));
    report.terminalResponseIncluded=true;
    report.coreDiagnostics=await page.evaluate(async()=>{const saved=JSON.parse(localStorage.getItem('fileflash.session.v1'));const response=await fetch('/api/diagnostics',{headers:{Authorization:'Bearer '+saved.session}});return response.ok?response.json():null;});
    fs.mkdirSync(path.dirname(reportPath),{recursive:true});fs.writeFileSync(reportPath,JSON.stringify(report,null,2)+'\n');
    console.log('PASS actual Android WebView: 60 MiB, '+puts.length+' acknowledged requests, '+report.uploadRequestSpanMs+' ms request span, '+report.mainThreadHashMs+' ms main-thread SHA, '+report.readMs+' ms reads, '+report.localStorageMs+' ms storage.');
  }finally{
    // CDP close disconnects this test session; the guarded harness owns its app.
    page.close();
  }
})().catch(error=>{console.error(error.message);process.exitCode=1;});
