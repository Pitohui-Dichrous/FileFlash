/* Single-request browser protocol, real HTTP, bounded Java heap and Node buffers.
 * Run after :core:installDist. Default source is 4 GiB + 65539 synthetic bytes.
 * Does not run a browser, phone, file picker or Wi-Fi. No resume behavior is used.
 */
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),http=require('node:http');
const {spawn}=require('node:child_process'),{once}=require('node:events');
const root=path.resolve(__dirname,'..'),privateRoot=path.join(root,'.test-data');
fs.mkdirSync(privateRoot,{recursive:true});
const work=fs.mkdtempSync(path.join(privateRoot,'browser-stream-')),runtime=path.join(work,'runtime');
fs.cpSync(path.join(root,'core/build/install/core/lib'),runtime,{recursive:true});
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const size=Number(process.env.FILEFLASH_TEST_BYTES||4*1024**3+65539),block=crypto.randomBytes(128*1024);
const version=fs.readFileSync(path.join(root,'app/build.gradle.kts'),'utf8').match(/versionName\s*=\s*"([^"]+)"/)[1];
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
const checks=[];let proc,base,session,ready,passed=false,nodePeakRss=0;
const started=Date.now(),partials=new Set();
function sample(){nodePeakRss=Math.max(nodePeakRss,process.memoryUsage().rss);}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function wait(predicate,timeout=10000){const deadline=Date.now()+timeout;while(Date.now()<deadline){try{const value=await predicate();if(value)return value;}catch(_){}await delay(50);}throw new Error('Controlled stream state did not reach its time bound.');}
async function start(){
  proc=spawn(java,['-Xmx128m','-cp',path.join(runtime,'*'),'app.fileflash.core.DesktopServer','--storage',work,'--assets',path.join(root,'app/src/main/assets/web'),'--port','0'],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});
  ready=await new Promise((resolve,reject)=>{
    let output='',error='';const timer=setTimeout(()=>reject(new Error('Controlled server readiness timeout.')),20000);
    proc.stdout.on('data',data=>{output+=data;const newline=output.indexOf('\n');if(newline>=0){clearTimeout(timer);try{resolve(JSON.parse(output.slice(0,newline)));}catch(error){reject(error);}}});
    proc.stderr.on('data',data=>{error=String(data).slice(-2000);fs.appendFileSync(path.join(work,'server-error.log'),data);});
    proc.once('exit',code=>{clearTimeout(timer);reject(new Error('Controlled server exited with code '+code+'. '+error));});
  });
  base=ready.url;session=(await api('POST','/api/pair',{token:ready.pairToken,name:'Controlled stream sender B'},201,false)).session;
}
async function stop(){if(proc&&proc.exitCode===null){const ended=once(proc,'exit');proc.kill('SIGKILL');await ended;}for(const request of partials)request.destroy();partials.clear();}
async function api(method,route,body,expected=200,auth=true,headers={}){
  const response=await fetch(base+route,{method,headers:{...(auth?{Authorization:'Bearer '+session}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  const result=await response.json();assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status),method+' controlled route returned HTTP '+response.status+' ('+(result.code||'unknown')+').');return result;
}
async function create(name,bytes){const item=await api('POST','/api/uploads',{name,size:bytes,mime:'application/octet-stream',restartOnly:true},201);assert.equal(item.offset,0);assert.equal(item.restartOnly,true);return item;}
const key=item=>item.resumeKey?{'X-Upload-Key':item.resumeKey}:{};
const partFile=item=>path.join(work,'uploads',item.id+'.part');

async function stream(item,bytes){
  const hasher=crypto.createHash('sha256'),requestId=crypto.randomUUID(),at=Date.now();let sent=0;
  const response=new Promise((resolve,reject)=>{
    const request=http.request(base+'/api/uploads/'+item.id+'/stream',{method:'POST',agent:false,headers:{Authorization:'Bearer '+session,'Content-Type':'application/octet-stream','Content-Length':String(bytes),'X-Request-ID':requestId,...key(item)}},reply=>{
      const chunks=[];reply.on('data',data=>chunks.push(data));reply.on('error',reject);reply.on('end',()=>{try{const result=JSON.parse(Buffer.concat(chunks).toString('utf8'));assert.equal(reply.statusCode,200,'Continuous stream HTTP status');resolve(result);}catch(error){reject(error);}});
    });
    request.setTimeout(120000,()=>request.destroy(new Error('Continuous test request timed out.')));request.on('error',reject);
    (async()=>{
      while(sent<bytes){const chunk=block.subarray(0,Math.min(block.length,bytes-sent));hasher.update(chunk);sent+=chunk.length;sample();if(!request.write(chunk))await once(request,'drain');}
      request.end();
    })().catch(error=>request.destroy(error));
  });
  const result=await response,digest=hasher.digest('hex');assert.equal(sent,bytes);assert.equal(result.status,'complete');assert.equal(result.offset,bytes);assert.equal(result.sha256,digest);
  return {result,sha256:digest,requestId,elapsedMs:Date.now()-at};
}

async function partial(item){
  const request=http.request(base+'/api/uploads/'+item.id+'/stream',{method:'POST',agent:false,headers:{Authorization:'Bearer '+session,'Content-Type':'application/octet-stream','Content-Length':String(item.size),...key(item)}});
  request.on('error',()=>{});request.on('response',response=>response.resume());request.flushHeaders();partials.add(request);
  for(let i=0;i<8;i++)if(!request.write(block))await once(request,'drain');
  await wait(()=>fs.existsSync(partFile(item))&&fs.statSync(partFile(item)).size>0);
  return request;
}

async function failureChecks(){
  let item=await create('controlled-disconnected.bin',8*1024*1024),request=await partial(item);
  request.destroy();partials.delete(request);await wait(()=>!fs.existsSync(partFile(item)));
  const failed=await api('GET','/api/uploads/'+item.id,undefined,[200,404,410],true,key(item));
  if(failed.offset!==undefined)assert.equal(failed.offset,0);
  assert.ok(!(await api('GET','/api/state')).files.some(file=>file.id===item.id));
  checks.push('disconnected stream removes partial bytes and never publishes an incomplete file');
  item=await create('controlled-cancelled.bin',8*1024*1024);request=await partial(item);
  await api('DELETE','/api/uploads/'+item.id,undefined,[200,404,410],true,key(item));request.destroy();partials.delete(request);
  await wait(()=>!fs.existsSync(partFile(item)));assert.ok(!(await api('GET','/api/state')).files.some(file=>file.id===item.id));
  checks.push('cancel interrupts active single request and removes partial bytes');
  item=await create('controlled-crashed.bin',8*1024*1024);request=await partial(item);
  assert.ok(fs.statSync(partFile(item)).size>0);await stop();assert.ok(fs.existsSync(partFile(item))&&fs.statSync(partFile(item)).size>0,'Hard process kill must leave a genuine crash residue before recovery.');await start();
  await wait(()=>!fs.existsSync(partFile(item)));assert.ok(!(await api('GET','/api/state')).files.some(file=>file.id===item.id));
  checks.push('hard process restart removes restart-only partial file instead of restoring a breakpoint');
}

async function main(){
  assert.ok(Number.isSafeInteger(size)&&size>0);await start();await failureChecks();
  const item=await create('controlled-large-continuous.bin',size),uploaded=await stream(item,size);
  assert.ok(!fs.existsSync(partFile(item)));checks.push('single HTTP request uploads exact declared length and server SHA-256 equals incremental source SHA-256');
  const receiver=(await api('POST','/api/pair',{token:ready.pairToken,name:'Controlled independent receiver C'},201,false)).session;
  const state=await api('GET','/api/state',undefined,200,false,{Authorization:'Bearer '+receiver}),file=state.files.find(file=>file.id===item.id);assert.ok(file);assert.equal(file.size,size);assert.equal(file.temporary,true);
  checks.push('independently paired receiver can access completed temporary relay without a host save');
  const rangeStart=Math.max(0,size-64),rangeEnd=size-1,ranged=await fetch(base+'/api/files/'+item.id,{headers:{Authorization:'Bearer '+receiver,Range:'bytes='+rangeStart+'-'+rangeEnd},signal:AbortSignal.timeout(30000)});
  assert.equal(ranged.status,206);assert.equal(ranged.headers.get('content-range'),'bytes '+rangeStart+'-'+rangeEnd+'/'+size);
  const expected=Buffer.alloc(rangeEnd-rangeStart+1);for(let i=0;i<expected.length;i++)expected[i]=block[(rangeStart+i)%block.length];assert.deepEqual(Buffer.from(await ranged.arrayBuffer()),expected);
  checks.push('Range offset '+rangeStart+' matches exact synthetic source bytes');
  const ticket=await api('POST','/api/files/'+item.id+'/ticket',{},200,false,{Authorization:'Bearer '+receiver});let received=0;const hasher=crypto.createHash('sha256'),downloadStarted=Date.now();
  await new Promise((resolve,reject)=>{
    http.get(base+ticket.url,response=>{assert.equal(response.statusCode,200);assert.equal(Number(response.headers['content-length']),size);response.on('data',data=>{received+=data.length;hasher.update(data);sample();});response.on('error',reject);response.on('aborted',()=>reject(new Error('Controlled download aborted.')));response.on('end',resolve);}).on('error',reject);
  });
  assert.equal(received,size);assert.equal(hasher.digest('hex'),uploaded.sha256);checks.push('independent C full streamed download matches source length and SHA-256');
  const diagnostics=await api('GET','/api/diagnostics'),trace=diagnostics.transport.requests.find(trace=>trace.clientRequestId===uploaded.requestId);assert.ok(trace);assert.equal(trace.receivedBytes,size);assert.equal(trace.status,200);
  const report={passed:true,version,bytes:size,sha256:uploaded.sha256,javaMaxHeapMiB:128,nodePeakRssMiB:Math.round(nodePeakRss/1024**2),sourceBlockBytes:block.length,dataRequests:1,uploadMs:uploaded.elapsedMs,downloadMs:Date.now()-downloadStarted,elapsedSeconds:Math.round((Date.now()-started)/1000),runtimeCoreSha256:sha(fs.readFileSync(path.join(runtime,'core.jar'))),checks,streamTrace:trace,environment:'Windows JVM real HTTP. Single-request browser protocol, no physical Android/iOS, browser file-picker or Wi-Fi claim.'};
  fs.mkdirSync(path.join(root,'docs/test-evidence'),{recursive:true});fs.writeFileSync(path.join(root,'docs/test-evidence/browser-stream-large.json'),JSON.stringify(report,null,2)+'\n');
  fs.mkdirSync(path.join(privateRoot,'reports'),{recursive:true});fs.writeFileSync(path.join(privateRoot,'reports/browser-stream-large.json'),JSON.stringify(report,null,2)+'\n');
  passed=true;console.log('PASS browser stream: '+size+' bytes in one request; SHA, independent C download/Range, failure/cancel/restart cleanup; Java heap128MiB.');
}
main().catch(error=>{console.error(error.message);process.exitCode=1;}).finally(async()=>{
  await stop();
  if(passed&&process.env.FILEFLASH_KEEP_TEST_DATA!=='1'&&path.dirname(path.resolve(work))===privateRoot&&/^browser-stream-[A-Za-z0-9]+$/.test(path.basename(work)))fs.rmSync(work,{recursive:true,force:true});
});
