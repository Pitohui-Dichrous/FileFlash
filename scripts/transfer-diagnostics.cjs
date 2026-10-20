/* Controlled transport probe. The ready file contains local test credentials.
 * node scripts/transfer-diagnostics.cjs private-ready.json report.json [--benchmark] [--probe] [--expect-continue]
 * Only synthetic bytes are sent. Reports omit credentials, URLs and upload IDs.
 * Simulated request headers and desktop browsers do not verify physical iOS.
 */
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const net=require('node:net');
const http=require('node:http');
const crypto=require('node:crypto');
const {performance}=require('node:perf_hooks');
const ready=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const reportPath=path.resolve(process.argv[3]||'docs/test-evidence/transfer-diagnostics.json');
const benchmark=process.argv.includes('--benchmark');
const probe=process.argv.includes('--probe'),expectContinue=process.argv.includes('--expect-continue');
const origin=new URL(ready.url);
assert.equal(origin.protocol,'http:','Use the local plain HTTP test server.');
const digest=data=>crypto.createHash('sha256').update(data).digest('hex');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const round=n=>Math.round(n*100)/100;
const iphone='Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';

async function api(method,pathname,session,data,headers={}) {
  const started=performance.now();
  return new Promise((resolve,reject)=>{
    const body=data===undefined?null:Buffer.isBuffer(data)?data:Buffer.from(JSON.stringify(data));
    const all={...(session?{Authorization:'Bearer '+session}:{}),...headers};
    if(body){all['Content-Length']=String(body.length);all['Content-Type']=Buffer.isBuffer(data)?'application/octet-stream':'application/json';}
    const request=http.request(new URL(pathname,origin),{method,headers:all,agent:false},response=>{
      const firstByteMs=round(performance.now()-started),chunks=[];
      response.on('data',chunk=>chunks.push(chunk));response.on('error',reject);
      response.on('end',()=>{
        const bytes=Buffer.concat(chunks);let result;
        try{result=JSON.parse(bytes.toString('utf8'));}catch(_){reject(new Error('Expected bounded JSON response for test API.'));return;}
        if(response.statusCode<200||response.statusCode>=300){reject(new Error(method+' test API returned HTTP '+response.statusCode+' ('+(result.code||'unknown')+').'));return;}
        resolve({result,firstByteMs,totalMs:round(performance.now()-started)});
      });
    });
    request.setTimeout(30000,()=>request.destroy(new Error('Controlled test request timed out.')));
    request.on('error',reject);request.end(body);
  });
}

async function rawRequest(method,pathname,session,body,extra={},mode='coalesced') {
  const started=performance.now(),headers={Host:origin.host,Authorization:'Bearer '+session,'Content-Type':'application/octet-stream','Content-Length':String(body.length),Connection:'keep-alive',...extra};
  const head=Buffer.from(method+' '+pathname+' HTTP/1.1\r\n'+Object.entries(headers).filter(([,value])=>value!==null).map(([key,value])=>key+': '+value).join('\r\n')+'\r\n\r\n','latin1');
  return new Promise((resolve,reject)=>{
    const socket=net.connect({host:origin.hostname,port:Number(origin.port)||80});
    let bytes=Buffer.alloc(0),settled=false,firstByteMs=null,bodySent=false;
    const finish=(error,value)=>{if(settled)return;settled=true;socket.destroy();if(error)reject(error);else resolve(value);};
    socket.setNoDelay(true);socket.setTimeout(5000,()=>finish(new Error('Raw '+mode+' request timed out.')));
    socket.on('error',error=>finish(error));
    socket.on('data',chunk=>{
      if(firstByteMs===null)firstByteMs=round(performance.now()-started);
      bytes=Buffer.concat([bytes,chunk]);if(bytes.length>1024*1024){finish(new Error('Probe response exceeded its bound.'));return;}
      while(true){
        const ending=bytes.indexOf('\r\n\r\n');if(ending<0)return;
        const text=bytes.subarray(0,ending).toString('latin1'),match=/^HTTP\/1\.1 (\d{3}) /.exec(text);
        if(!match){finish(new Error('Malformed test response.'));return;}
        const status=Number(match[1]);if(status===100){bytes=bytes.subarray(ending+4);if(!bodySent){bodySent=true;socket.write(body);}continue;}
        const length=/\r\nContent-Length:\s*(\d+)/i.exec(text);if(!length){finish(new Error('Missing bounded response length.'));return;}
        const size=Number(length[1]);if(bytes.length<ending+4+size)return;
        let result;try{result=JSON.parse(bytes.subarray(ending+4,ending+4+size).toString('utf8'));}catch(_){finish(new Error('Expected JSON response.'));return;}
        finish(null,{status,result,firstByteMs,totalMs:round(performance.now()-started)});return;
      }
    });
    socket.on('end',()=>{if(!settled)finish(new Error('Response ended before its declared length.'));});
    socket.on('connect',()=>{(async()=>{
      if(mode==='coalesced'){bodySent=true;socket.write(Buffer.concat([head,body]));}
      else if(mode==='delayed-body'){socket.write(head);await sleep(120);if(!settled){bodySent=true;socket.write(body);}}
      else if(mode==='fragmented-headers'){
        for(let offset=0;offset<head.length;offset+=31){if(settled)return;socket.write(head.subarray(offset,offset+31));await sleep(2);}bodySent=true;socket.write(body);
      }else if(mode==='fragmented-body'){
        bodySent=true;socket.write(head);for(let offset=0;offset<body.length;offset+=16384){if(settled)return;socket.write(body.subarray(offset,offset+16384));await sleep(1);}
      }else if(mode==='headers-only')socket.write(head);
      else throw new Error('Unknown probe mode.');
    })().catch(error=>finish(error));});
  });
}

async function framingChecks(session) {
  const data=crypto.randomBytes(512*1024+37),sha=digest(data),results=[];
  for(const mode of ['coalesced','delayed-body','fragmented-headers','fragmented-body']) {
    const task=(await api('POST','/api/uploads',session,{name:'controlled-'+mode+'.bin',size:data.length,mime:'application/octet-stream'})).result;
    const put=await rawRequest('PUT','/api/uploads/'+task.id,session,data,{'X-Upload-Key':task.resumeKey,'X-Offset':'0','X-Chunk-SHA256':sha,'User-Agent':iphone,Accept:'*/*',Origin:origin.origin,Referer:origin.origin+'/'},mode);
    assert.equal(put.status,200,mode);assert.equal(put.result.offset,data.length,mode);
    const completed=(await api('POST','/api/uploads/'+task.id+'/complete',session,{sha256:sha},{'X-Upload-Key':task.resumeKey})).result;
    assert.equal(completed.sha256,sha);
    results.push({mode,bodyBytes:data.length,status:put.status,acknowledgedBytes:put.result.offset,firstResponseByteMs:put.firstByteMs,totalMs:put.totalMs,finalSha256Matches:true});
  }
  const task=(await api('POST','/api/uploads',session,{name:'controlled-framing-rejections.bin',size:data.length,mime:'application/octet-stream'})).result;
  let acknowledged=0;
  for(const variant of ['expect-continue','transfer-encoding']){
    const extra={'X-Upload-Key':task.resumeKey,'X-Offset':'0','X-Chunk-SHA256':sha};
    if(variant==='expect-continue')extra.Expect='100-continue';
    else{extra['Content-Length']=null;extra['Transfer-Encoding']='chunked';}
    const response=await rawRequest('PUT','/api/uploads/'+task.id,session,data,extra,'headers-only');
    if(variant==='expect-continue'){
      assert.ok((expectContinue?[200]:[200,417]).includes(response.status));
      if(response.status===200)acknowledged=data.length;
    }else assert.equal(response.status,400);
    assert.ok(response.totalMs<3000,'Unsupported framing must return an error promptly.');
    const state=(await api('GET','/api/uploads/'+task.id,session,undefined,{'X-Upload-Key':task.resumeKey})).result;
    assert.equal(state.offset,acknowledged);
    results.push({mode:variant,status:response.status,...(response.result.code?{errorCode:response.result.code}:{}),totalMs:response.totalMs,acknowledgedBytes:acknowledged});
  }
  await api('DELETE','/api/uploads/'+task.id,session,undefined,{'X-Upload-Key':task.resumeKey});
  return results;
}

async function diagnosticChecks(session){
  const before=(await api('GET','/api/state',session)).result,data=crypto.randomBytes(512*1024),sha=digest(data),results=[];
  for(const method of ['PUT','POST']){
    const id=crypto.randomUUID(),reply=await api(method,'/api/diagnostics/probe',session,data,{'X-Request-ID':id});
    assert.deepEqual(reply.result,{length:data.length,sha256:sha});
    results.push({method,requestId:id,length:reply.result.length,sha256Matches:true,totalMs:reply.totalMs});
  }
  const oversized=await rawRequest('POST','/api/diagnostics/probe',session,Buffer.alloc(512*1024+1));assert.equal(oversized.status,413);
  const unauthenticated=await rawRequest('GET','/api/diagnostics',null,Buffer.alloc(0),{Authorization:null});assert.equal(unauthenticated.status,401);
  const after=(await api('GET','/api/state',session)).result;
  assert.equal(after.uploads.length,before.uploads.length);assert.equal(after.files.length,before.files.length);
  const snapshot=(await api('GET','/api/diagnostics',session)).result;
  assert.equal(snapshot.schema,1);assert.equal(snapshot.running,true);assert.equal(snapshot.transport.capacity,128);
  assert.ok(snapshot.transport.requests.length<=snapshot.transport.capacity);
  for(const [key,max] of [['workersActive',6],['workersQueued',18],['pendingHeaders',48],['openConnections',72]])assert.ok(snapshot.transport[key]>=0&&snapshot.transport[key]<=max,key);
  const serialized=JSON.stringify(snapshot);
  for(const secret of [session,ready.pairToken,origin.origin,'controlled-framing-rejections.bin'])assert.ok(!serialized.includes(secret),'Sanitized diagnostics omitted local credentials, addresses and filenames.');
  for(const item of results){const trace=snapshot.transport.requests.find(request=>request.clientRequestId===item.requestId);assert.ok(trace);assert.equal(trace.route,'diagnostic-probe');assert.equal(trace.receivedBytes,data.length);assert.equal(trace.status,200);assert.equal(trace.flushed,true);assert.ok(trace.atMs['body-first-byte']<=trace.atMs['body-complete']);}
  return {schema:1,probes:results,oversizedStatus:oversized.status,unauthenticatedStatus:unauthenticated.status,persistentTasksUnchanged:true,persistentFilesUnchanged:true,sanitized:true,snapshot};
}

async function singleStreamChecks(session){
  const data=crypto.randomBytes(10*1024*1024+17),sha=digest(data),task=(await api('POST','/api/uploads',session,{name:'controlled-single-stream-'+Date.now()+'.bin',size:data.length,mime:'application/octet-stream',restartOnly:true})).result;
  assert.equal(task.restartOnly,true);const headers={'X-Upload-Key':task.resumeKey};
  const completed=(await api('POST','/api/uploads/'+task.id+'/stream',session,data,headers)).result;assert.equal(completed.offset,data.length);assert.equal(completed.status,'complete');assert.equal(completed.sha256,sha);
  const state=(await api('GET','/api/state',session)).result,file=state.files.find(file=>file.id===task.id);assert.ok(file);assert.equal(file.temporary,true);
  const response=await fetch(new URL('/api/files/'+task.id,origin),{headers:{Authorization:'Bearer '+session}});assert.equal(response.status,200);assert.equal(digest(Buffer.from(await response.arrayBuffer())),sha);
  await api('DELETE','/api/uploads/'+task.id,session,undefined,headers);
  assert.ok(!(await api('GET','/api/state',session)).result.files.some(file=>file.id===task.id));
  const gone=await fetch(new URL('/api/files/'+task.id,origin),{headers:{Authorization:'Bearer '+session}});assert.equal(gone.status,404);
  return {fileBytes:data.length,dataRequests:1,finalSha256Matches:true,downloadBytesMatch:true,temporaryRelay:true,completedUnsavedStreamCanBeDiscarded:true};
}

async function legacyChunkChecks(session){
  const data=crypto.randomBytes(2*1024*1024+17),sha=digest(data),task=(await api('POST','/api/uploads',session,{name:'controlled-native-protocol-'+Date.now()+'.bin',size:data.length,mime:'application/octet-stream'})).result;
  const first=data.subarray(0,2*1024*1024),headers={'X-Upload-Key':task.resumeKey,'X-Offset':'0','X-Chunk-SHA256':digest(first)};
  assert.equal((await api('PUT','/api/uploads/'+task.id,session,first,headers)).result.offset,first.length);
  assert.equal((await api('POST','/api/uploads/'+task.id+'/chunk',session,first,headers)).result.offset,first.length);
  const tail=data.subarray(first.length),answer=(await api('PUT','/api/uploads/'+task.id,session,tail,{...headers,'X-Offset':String(first.length),'X-Chunk-SHA256':digest(tail)})).result;assert.equal(answer.offset,data.length);
  const completed=(await api('POST','/api/uploads/'+task.id+'/complete',session,{sha256:sha},{'X-Upload-Key':task.resumeKey})).result;assert.equal(completed.sha256,sha);
  return {fileBytes:data.length,legacyPutChunkBytes:first.length,postAliasDuplicateIdempotent:true,finalSha256Matches:true};
}

async function nativeBenchmark(session,data) {
  const task=(await api('POST','/api/uploads',session,{name:'controlled-native-60MiB.bin',size:data.length,mime:'application/octet-stream'})).result;
  const puts=[],started=performance.now();let previousAck=started;
  for(let offset=0;offset<data.length;offset+=2*1024*1024){
    const block=data.subarray(offset,Math.min(offset+2*1024*1024,data.length)),hashStarted=performance.now(),sha=digest(block),hashMs=round(performance.now()-hashStarted);
    const sent=performance.now(),reply=await api('PUT','/api/uploads/'+task.id,session,block,{'X-Upload-Key':task.resumeKey,'X-Offset':String(offset),'X-Chunk-SHA256':sha,'User-Agent':'Dalvik controlled HTTP fixture'});
    const ack=performance.now();assert.equal(reply.result.offset,offset+block.length);
    puts.push({offset,bodyBytes:block.length,hashMs,betweenPreviousAckAndSendMs:round(sent-previousAck),requestMs:reply.totalMs,firstResponseByteMs:reply.firstByteMs,ackGapMs:round(ack-previousAck)});previousAck=ack;
  }
  const uploadMs=round(performance.now()-started),completeStarted=performance.now(),complete=(await api('POST','/api/uploads/'+task.id+'/complete',session,{sha256:digest(data)},{'X-Upload-Key':task.resumeKey})).result;
  assert.equal(complete.sha256,digest(data));
  return {profile:'Node HTTP requests with native client chunk size; not Android runtime',fileBytes:data.length,chunkBytes:2*1024*1024,uploadMs,completeMs:round(performance.now()-completeStarted),totalMs:round(performance.now()-started),finalSha256Matches:true,puts};
}

async function browserBenchmark(data) {
  const playwright=require('playwright'),engine=process.env.FILEFLASH_BROWSER_ENGINE||'chromium';
  const browser=await playwright[engine].launch(engine==='chromium'?{headless:true,channel:process.env.FILEFLASH_BROWSER_CHANNEL||'chrome'}:{headless:true});
  const sourceDir=fs.mkdtempSync(path.resolve(__dirname,'../.test-data/transfer-source-'));
  const name='controlled-browser-60MiB-'+Date.now()+'.bin',sourceFile=path.join(sourceDir,name);
  fs.writeFileSync(sourceFile,data);
  try{
    const context=await browser.newContext({acceptDownloads:true,reducedMotion:'reduce'}),page=await context.newPage(),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(()=>{
      window.controlledTransferMetrics={reads:[],requests:[],fullHashes:[]};
      const read=Blob.prototype.arrayBuffer;
      Blob.prototype.arrayBuffer=async function(){const start=performance.now(),result=await read.call(this);window.controlledTransferMetrics.reads.push({bytes:this.size,start,end:performance.now()});return result;};
      const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open=function(method,url){this.__controlledMethod=method;this.__controlledUrl=String(url);return open.apply(this,arguments);};
      XMLHttpRequest.prototype.send=function(data){
        if(['PUT','POST'].includes(this.__controlledMethod)&&/^\/api\/uploads\/[^/]+(?:\/(?:chunk|stream))?$/.test(this.__controlledUrl)){
          const item={bodyBytes:typeof data.byteLength==='number'?data.byteLength:data.size,start:performance.now(),uploaded:null,ack:null,status:null};window.controlledTransferMetrics.requests.push(item);
          this.upload.addEventListener('load',()=>{item.uploaded=performance.now();});
          this.addEventListener('loadend',()=>{item.ack=performance.now();item.status=this.status;try{item.sha256=JSON.parse(this.responseText).sha256||null;}catch(_){}});
        }return send.apply(this,arguments);
      };
    });
    await page.goto(origin.origin+'/#pair='+encodeURIComponent(ready.pairToken));
    await page.locator('#connection-label').filter({hasText:'已连接'}).waitFor({timeout:30000});
    const started=performance.now();
    await page.locator('#file-input').setInputFiles(sourceFile);
    await page.locator('.task-state').filter({hasText:'已完成'}).waitFor({timeout:180000});
    const totalMs=round(performance.now()-started),metrics=await page.evaluate(()=>window.controlledTransferMetrics),task=await page.evaluate(name=>JSON.parse(localStorage.getItem('fileflash.tasks.v1')).find(task=>task.name===name),name);
    assert.equal(task.sha256||metrics.requests.at(-1).sha256,digest(data));assert.equal(task.offset,data.length);assert.deepEqual(errors,[]);
    assert.ok(metrics.requests.length>=1);assert.ok(metrics.requests.every(request=>request.status===200));
    let previousAck=metrics.requests[0].start;
    const puts=metrics.requests.map(request=>{
      const item={bodyBytes:request.bodyBytes,betweenPreviousAckAndSendMs:round(request.start-previousAck),uploadEventMs:request.uploaded===null?null:round(request.uploaded-request.start),requestMs:round(request.ack-request.start),ackGapMs:round(request.ack-previousAck)};previousAck=request.ack;return item;
    });
    return {profile:'Actual app.js in desktop '+engine+'; not physical iOS/Android browser',browserVersion:browser.version(),fileBytes:data.length,totalMs,uploadRequestSpanMs:round(metrics.requests.at(-1).ack-metrics.requests[0].start),readMs:round(metrics.reads.reduce((sum,read)=>sum+read.end-read.start,0)),firstReadBytes:metrics.reads.length?metrics.reads[0].bytes:0,firstPutBytes:puts[0].bodyBytes,finalSha256Matches:true,pageErrors:errors,puts};
  }finally{await browser.close();fs.unlinkSync(sourceFile);fs.rmdirSync(sourceDir);}
}

(async()=>{
  const session=(await api('POST','/api/pair',null,{token:ready.pairToken,name:'Controlled transport diagnostics'})).result.session;
  const report={passed:false,recordedAt:new Date().toISOString(),scope:'Synthetic Node HTTP framing and optional actual desktop browser UI. No physical iOS, Wi-Fi, personal file or phone-runtime claim.',framing:await framingChecks(session)};
  if(ready.version)report.version=ready.version;
  if(ready.serverApkSha256)report.serverApkSha256=ready.serverApkSha256;
  if(benchmark){const data=crypto.randomBytes(60*1024*1024);report.benchmarks=[await nativeBenchmark(session,data),await browserBenchmark(data)];}
  if(probe)report.diagnostics=await diagnosticChecks(session);
  if(probe)report.singleStreamUpload=await singleStreamChecks(session);
  if(probe)report.legacyChunkUpload=await legacyChunkChecks(session);
  report.passed=true;fs.mkdirSync(path.dirname(reportPath),{recursive:true});fs.writeFileSync(reportPath,JSON.stringify(report,null,2)+'\n');
  console.log('PASS controlled framing variants: '+report.framing.length+(benchmark?'; 60 MiB HTTP and actual browser benchmark completed.':'.'));
  if(benchmark)for(const item of report.benchmarks)console.log(item.profile+': '+item.totalMs+' ms.');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
