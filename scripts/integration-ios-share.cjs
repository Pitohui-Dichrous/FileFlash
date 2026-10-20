/* Direct File-body endpoint integration, not a run of iOS or Shortcuts.
 * Requires :core:installDist. Default: 4 GiB + 65539 bytes per framing mode,
 * Java heap 128 MiB, one reusable random 128 KiB source block.
 * All server state and copied runtime are restricted to this run's mkdtemp.
 */
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),http=require('node:http');
const {spawn}=require('node:child_process'),{once}=require('node:events');
const root=path.resolve(__dirname,'..'),privateRoot=path.join(root,'.test-data');
fs.mkdirSync(privateRoot,{recursive:true});
const work=fs.mkdtempSync(path.join(privateRoot,'ios-share-large-'));
const storage=path.join(work,'storage'),runtime=path.join(work,'runtime');
fs.mkdirSync(storage);
fs.cpSync(path.join(root,'core/build/install/core/lib'),runtime,{recursive:true});
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const bytes=Number(process.env.FILEFLASH_TEST_BYTES||4*1024**3+65539);
const block=crypto.randomBytes(128*1024),checks=[],modes=[],partials=new Set();
const started=Date.now(),version=fs.readFileSync(path.join(root,'app/build.gradle.kts'),'utf8').match(/versionName\s*=\s*"([^"]+)"/)[1];
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
let proc,ready,base,session,passed=false,nodePeakRss=0;
function sample(){nodePeakRss=Math.max(nodePeakRss,process.memoryUsage().rss);}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function wait(predicate,timeout=15000){
  const until=Date.now()+timeout;
  while(Date.now()<until){try{const result=await predicate();if(result)return result;}catch(_){}await delay(40);}
  throw new Error('This run did not reach the expected server state within '+timeout+' ms.');
}
async function api(method,route,body,expected=200,token=session){
  const response=await fetch(base+route,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  const result=await response.json();
  assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status),method+' fixture route HTTP '+response.status+' '+(result.code||''));
  return result;
}
async function start(){
  proc=spawn(java,['-Xmx128m','-cp',path.join(runtime,'*'),'app.fileflash.core.DesktopServer','--storage',storage,'--assets',path.join(root,'app/src/main/assets/web'),'--port','0'],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
  ready=await new Promise((resolve,reject)=>{
    let output='',stderr='';const timer=setTimeout(()=>reject(new Error('This run server readiness timeout.')),20000);
    proc.stdout.on('data',data=>{output+=data;const end=output.indexOf('\n');if(end>=0){clearTimeout(timer);try{resolve(JSON.parse(output.slice(0,end)));}catch(error){reject(error);}}});
    proc.stderr.on('data',data=>{stderr=String(data).slice(-1500);fs.appendFileSync(path.join(work,'server-stderr.log'),data);});
    proc.once('exit',code=>{clearTimeout(timer);reject(new Error('This run server exited '+code+' '+stderr));});
  });
  base=ready.url;
  session=(await api('POST','/api/pair',{token:ready.pairToken,name:'Synthetic native-share sender B'},201,null)).session;
}
async function stop(){
  for(const request of partials)request.destroy();partials.clear();
  if(proc&&proc.exitCode===null){const ended=once(proc,'exit');proc.kill('SIGKILL');await ended;}
}
function records(){
  return ['uploads','received'].flatMap(folder=>{
    const directory=path.join(storage,folder);
    return fs.existsSync(directory)?fs.readdirSync(directory).filter(name=>fs.statSync(path.join(directory,name)).isFile()).map(name=>folder+'/'+name):[];
  });
}
async function clean(){
  await wait(async()=>{
    if(records().length)return false;
    const state=await api('GET','/api/state');return state.files.length===0&&state.uploads.length===0;
  });
  assert.deepEqual(records(),[]);
}
function headers(mode,length,requestId){
  assert.ok(mode==='content-length'||mode==='chunked');
  return {Authorization:'Bearer '+session,'Content-Type':'application/octet-stream','X-Request-ID':requestId,
    ...(mode==='content-length'?{'Content-Length':String(length)}:{'Transfer-Encoding':'chunked'})};
}
function target(name){return '/api/shortcuts/files?name='+encodeURIComponent(name);}
async function directUpload(mode,length,name){
  const began=Date.now(),requestId=crypto.randomUUID(),hasher=crypto.createHash('sha256');let sent=0;
  const requestHeaders=headers(mode,length,requestId);
  assert.ok(!Object.keys(requestHeaders).some(key=>/sha|checksum|upload-key/i.test(key)));
  const result=await new Promise((resolve,reject)=>{
    const request=http.request(base+target(name),{method:'POST',agent:false,headers:requestHeaders},response=>{
      const chunks=[];let accumulated=0;
      response.on('data',data=>{accumulated+=data.length;if(accumulated>65536){response.destroy(new Error('Receipt is unexpectedly large.'));return;}chunks.push(data);});
      response.on('error',reject);response.on('aborted',()=>reject(new Error('Direct upload receipt aborted.')));
      response.on('end',()=>{try{const receipt=JSON.parse(Buffer.concat(chunks).toString('utf8'));assert.equal(response.statusCode,200,'Direct upload HTTP status '+JSON.stringify(receipt));resolve(receipt);}catch(error){reject(error);}});
    });
    request.on('error',reject);request.setTimeout(120000,()=>request.destroy(new Error('Direct upload timed out.')));
    (async()=>{
      while(sent<length){
        // A unique 64-bit block ordinal makes a wrongly truncated >4 GiB Range
        // observably different; a purely repeating 128 KiB block would not.
        block.writeBigUInt64BE(BigInt(Math.floor(sent/block.length)),0);
        const chunk=block.subarray(0,Math.min(block.length,length-sent));hasher.update(chunk);sent+=chunk.length;sample();
        // Wait until Node has consumed this reusable buffer before mutating it.
        await new Promise((written,failed)=>request.write(chunk,error=>error?failed(error):written()));
      }
      request.end();
    })().catch(error=>request.destroy(error));
  });
  assert.equal(sent,length);assert.equal(result.status,'complete');assert.equal(result.size,length);assert.equal(result.offset,length);
  assert.equal(result.temporary,true);assert.equal(result.restartOnly,true);assert.equal(result.name,name);assert.ok(!('resumeKey' in result));
  const digest=hasher.digest('hex');assert.equal(result.sha256,digest);
  assert.ok(!fs.existsSync(path.join(storage,'uploads',result.id+'.part')));
  return {result,sha256:digest,requestId,elapsedMs:Date.now()-began,dataRequests:1,senderChecksumPrepass:false};
}
async function partial(mode,label){
  const name=label+'-'+mode+'.mov',request=http.request(base+target(name),{method:'POST',agent:false,headers:headers(mode,8*1024*1024,crypto.randomUUID())});
  request.on('error',()=>{});request.on('response',response=>response.resume());request.flushHeaders();partials.add(request);
  for(let i=0;i<8;i++)if(!request.write(block))await once(request,'drain');
  const task=await wait(async()=>{
    const state=await api('GET','/api/state'),task=state.uploads.find(upload=>upload.name===name);
    if(!task)return false;
    const file=path.join(storage,'uploads',task.id+'.part');
    return fs.existsSync(file)&&fs.statSync(file).size>0?task:false;
  });
  return {request,id:task.id,part:path.join(storage,'uploads',task.id+'.part'),bytesBeforeFailure:fs.statSync(path.join(storage,'uploads',task.id+'.part')).size};
}
async function failures(mode){
  const results=[];
  let active=await partial(mode,'disconnect');
  active.request.destroy();partials.delete(active.request);await clean();
  results.push({case:'client socket disconnect',passed:true,prefixBytes:active.bytesBeforeFailure,remainingRecords:0});
  active=await partial(mode,'cancel');
  await api('DELETE','/api/uploads/'+active.id,undefined,200);
  active.request.destroy();partials.delete(active.request);await clean();
  results.push({case:'owner cancels active upload',passed:true,prefixBytes:active.bytesBeforeFailure,remainingRecords:0});
  active=await partial(mode,'process-kill');
  // Kill the JVM first so recovery is tested against an actual incomplete file;
  // closing the client before the kill would allow ordinary disconnect cleanup.
  const ended=once(proc,'exit');proc.kill('SIGKILL');await ended;
  assert.ok(fs.existsSync(active.part)&&fs.statSync(active.part).size>0,'A hard kill must leave a genuine byte prefix before recovery.');
  const residue=fs.statSync(active.part).size;
  for(const request of partials)request.destroy();partials.clear();
  await start();await clean();
  results.push({case:'hard JVM kill then restart recovery',passed:true,prefixBytes:residue,remainingRecords:0});
  checks.push(mode+': disconnect, owner cancellation and hard process restart leave no incomplete bytes, metadata or shares');
  return results;
}
async function receive(uploaded,receiver){
  const {id}=uploaded.result;
  const state=await api('GET','/api/state',undefined,200,receiver),visible=state.files.find(file=>file.id===id);
  assert.ok(visible);assert.equal(visible.temporary,true);assert.equal(visible.size,bytes);
  const crosses4GiB=bytes>4*1024**3+32770;
  const begin=crosses4GiB?4*1024**3-32769:Math.max(0,bytes-64),end=crosses4GiB?4*1024**3+32770:bytes-1;
  const ranged=await fetch(base+'/api/files/'+id,{headers:{Authorization:'Bearer '+receiver,Range:'bytes='+begin+'-'+end},signal:AbortSignal.timeout(30000)});
  assert.equal(ranged.status,206);assert.equal(ranged.headers.get('content-range'),'bytes '+begin+'-'+end+'/'+bytes);
  const expected=Buffer.alloc(end-begin+1);
  for(let i=0;i<expected.length;i++){
    const position=begin+i,index=position%block.length;
    expected[i]=index<8?Number((BigInt(Math.floor(position/block.length))>>BigInt(8*(7-index)))&255n):block[index];
  }
  assert.deepEqual(Buffer.from(await ranged.arrayBuffer()),expected);
  const began=Date.now(),hasher=crypto.createHash('sha256');let received=0;
  await new Promise((resolve,reject)=>{
    const request=http.get(base+'/api/files/'+id,{agent:false,headers:{Authorization:'Bearer '+receiver}},response=>{
      if(response.statusCode!==200){response.resume();reject(new Error('Independent receiver HTTP '+response.statusCode));return;}
      assert.equal(Number(response.headers['content-length']),bytes);
      response.on('data',data=>{received+=data.length;hasher.update(data);sample();});
      response.on('error',reject);response.on('aborted',()=>reject(new Error('Independent download aborted.')));response.on('end',resolve);
    });
    request.on('error',reject);request.setTimeout(120000,()=>request.destroy(new Error('Independent download timed out.')));
  });
  assert.equal(received,bytes);const digest=hasher.digest('hex');assert.equal(digest,uploaded.sha256);
  return {bytes:received,sha256:digest,elapsedMs:Date.now()-began,independentlyPairedReceiver:true,
    range:{start:begin,end,crosses4GiB,bytes:expected.length,exactSourceBytes:true}};
}
async function run(mode){
  const began=Date.now(),failureCases=await failures(mode);
  const receiver=(await api('POST','/api/pair',{token:ready.pairToken,name:'Synthetic independent receiver C'},201,null)).session;
  const uploaded=await directUpload(mode,bytes,'large '+mode+' + 原片.mov'),download=await receive(uploaded,receiver);
  const diagnostics=await api('GET','/api/diagnostics');
  const trace=diagnostics.transport.requests.find(request=>request.clientRequestId===uploaded.requestId);
  assert.ok(trace);assert.equal(trace.status,200);assert.equal(trace.receivedBytes,bytes);
  assert.equal(trace.route,'shortcut-upload');
  assert.equal(trace.hasContentLength,mode==='content-length');
  assert.equal(trace.transferEncoding,mode==='chunked'?'chunked':'none');
  assert.equal(trace.contentLength,mode==='content-length'?bytes:-1);
  await api('DELETE','/api/uploads/'+uploaded.result.id,undefined,200);await clean();
  checks.push(mode+': one direct POST, no create-upload request or sender checksum pass; exact length and source/server SHA-256');
  checks.push(mode+': independent C sees temporary file, full download SHA-256 and boundary Range match source; owner deletion removes bytes/metadata');
  return {mode,passed:true,bytes,sha256:uploaded.sha256,dataRequests:uploaded.dataRequests,createUploadRequests:0,senderChecksumPrepass:false,
    uploadMs:uploaded.elapsedMs,download,failureCases,remainingRecordsAfterOwnerDelete:0,elapsedMs:Date.now()-began,
    transport:{route:trace.route,status:trace.status,receivedBytes:trace.receivedBytes,declaredLength:trace.contentLength,
      hasContentLength:trace.hasContentLength,transferEncoding:trace.transferEncoding}};
}
function report(error){
  return {passed,version,bytesPerMode:bytes,totalUploadedBytes:modes.reduce((sum,mode)=>sum+mode.bytes,0),javaMaxHeapMiB:128,
    sourceBlockBytes:block.length,sourceBlockOrdinalBytes:8,nodePeakRssMiB:Math.round(nodePeakRss/1024**2),elapsedSeconds:Math.round((Date.now()-started)/1000),
    runtimeCoreSha256:sha(fs.readFileSync(path.join(runtime,'core.jar'))),modes,checks,
    ...(error?{error:String(error.message||error)}:{}),
    environment:'Windows JVM real HTTP, synthetic reusable random source block with unique 64-bit ordinal per block. Tests direct File-body protocol with Content-Length and chunked framing. Does not run iOS, Shortcuts, Photos, a browser picker, physical Android or Wi-Fi; does not prove iOS import, media export, memory use or transfer performance.'};
}
async function main(){
  assert.ok(Number.isSafeInteger(bytes)&&bytes>0,'FILEFLASH_TEST_BYTES must be a positive safe integer.');
  await start();
  for(const mode of ['content-length','chunked'])modes.push(await run(mode));
  passed=true;
  const result=report();fs.mkdirSync(path.join(root,'docs/test-evidence'),{recursive:true});
  fs.writeFileSync(path.join(root,'docs/test-evidence/ios-share-large.json'),JSON.stringify(result,null,2)+'\n');
  console.log('PASS native-share direct protocol: '+bytes+' bytes x 2 framing modes; independent C SHA/Range; failure/cancel/restart/delete cleanup.');
}
main().catch(error=>{
  process.exitCode=1;console.error(error.stack||error);
  fs.writeFileSync(path.join(work,'failed-report.json'),JSON.stringify(report(error),null,2)+'\n');
}).finally(async()=>{
  await stop();
  const resolved=path.resolve(work);
  if(passed&&process.env.FILEFLASH_KEEP_TEST_DATA!=='1'&&path.dirname(resolved)===privateRoot&&/^ios-share-large-[A-Za-z0-9]+$/.test(path.basename(resolved)))fs.rmSync(resolved,{recursive:true,force:true});
});
