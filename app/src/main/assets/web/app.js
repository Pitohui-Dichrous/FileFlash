/* FileFlash browser client. No remote assets, framework, or secure-context API required. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const IOS = /iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
  const DEFAULT_UPLOAD_CHANNEL='xhr-post-file';
  const HISTORY_LIMIT = 100, HISTORY_TTL = 7 * 24 * 60 * 60 * 1000;
  const SESSION_KEY = 'fileflash.session.v1', TASKS_KEY = 'fileflash.tasks.v1';
  const pendingHashToken = new URLSearchParams(location.hash.slice(1)).get('pair');
  // Remove the pairing secret before any subsequent navigation or request.
  if (location.hash) history.replaceState(null, '', location.pathname + location.search);
  let saved = readStore(SESSION_KEY, null), session = saved && saved.session;
  let clientId = saved && saved.clientId, connection = 'disconnected', tab = 'files';
  let server = { files: [], texts: [], uploads: [], downloads: [] };
  const storedTasks = readStore(TASKS_KEY, []);
  let tasks = (Array.isArray(storedTasks) ? storedTasks : []).filter(validStoredTask).map(t => Object.assign(t, {
    key: t.key || localId(), status: ['complete', 'cancelled'].includes(t.status) ? t.status : 'failed',
    file: null, speed: 0, loaded: 0,
    error: ['complete', 'cancelled'].includes(t.status) ? t.error : '重新选择文件以重传。'
  }));
  let busy = false, current = null, refreshing = false, poll = null, targetSelection = null, textSending = false;
  let uploadChannel=DEFAULT_UPLOAD_CHANNEL;
  let filesSignature = '', textsSignature = '', storageWarned = false, renderPending = false;
  let toastTimer, seenTexts = new Set();
  const taskRows = new Map(), textRows = new Map();
  function readStore(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) || fallback; } catch (_) { return fallback; } }
  function store(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (_) {
    if (!storageWarned) { storageWarned = true; toast('浏览器无法保存连接与进度；关闭页面后需要重新配对。', 7000); }
  } }
  function validStoredTask(t) { return t && typeof t.name === 'string' && Number.isSafeInteger(t.size) && t.size >= 0 && (!t.id || /^[A-Za-z0-9_-]+$/.test(t.id)); }
  function terminalTask(task) { return ['complete', 'cancelled'].includes(task.status); }
  function finishTask(task, status) { task.status = status; task.file = null; task.phase=''; delete task.resumeKey; task.finishedAt = Date.now(); }
  function trimHistory() {
    // History is metadata only. Keep unfinished tasks and pending cleanup keys.
    const cutoff = Date.now() - HISTORY_TTL;
    const endedAt = task => Number(task.finishedAt) || Number(task.createdAt) || 0;
    const recent = new Set(tasks.slice().reverse().filter(task => terminalTask(task) && endedAt(task) >= cutoff).sort((a,b) => endedAt(b) - endedAt(a)).slice(0,HISTORY_LIMIT));
    tasks = tasks.filter(task => {
      if (!terminalTask(task)) return true;
      task.file = null; delete task.resumeKey;
      return recent.has(task);
    });
  }
  function persistTasks() {
    trimHistory();
    store(TASKS_KEY, tasks.map(t => ({ key:t.key,id:t.id,resumeKey:t.resumeKey,name:t.name,size:t.size,mime:t.mime,offset:t.offset||0,status:t.status,createdAt:t.createdAt,finishedAt:t.finishedAt,error:t.error||'' })));
  }
  function localId() { return 'local-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2); }
  function node(tag, cls, text) { const el = document.createElement(tag); if (cls) el.className = cls; if (text !== undefined) el.textContent = text; return el; }
  function icon(name) { const el = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); el.setAttribute('aria-hidden', 'true'); const use = document.createElementNS('http://www.w3.org/2000/svg', 'use'); use.setAttribute('href', '#i-' + name); el.append(use); return el; }
  function button(label, cls, click, symbol) { const el = node('button', 'button ' + (cls || '')); el.type = 'button'; if(symbol) el.append(icon(symbol)); el.append(document.createTextNode(label)); el.addEventListener('click', click); return el; }
  function message(el, text) { el.textContent = text || ''; el.hidden = !text; }
  function toast(text, duration) { clearTimeout(toastTimer); message($('toast'), text); toastTimer = setTimeout(() => { $('toast').hidden = true; }, duration || 4000); }
  function bytes(n) { if (!Number.isFinite(n) || n < 0) return '—'; if (n < 1024) return n + ' B'; const i = Math.min(4, Math.floor(Math.log(n) / Math.log(1024))); return (n / Math.pow(1024,i)).toFixed(i === 1 ? 1 : 2) + ' ' + ['B','KB','MB','GB','TB'][i]; }
  function date(value) { if (!value) return ''; const d = new Date(value); return isNaN(d.getTime()) ? '' : d.toLocaleString('zh-CN', { month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit' }); }
  function errorText(err) { if (err && err.name === 'AbortError') return '操作已中断，可重试。'; return err && err.message ? err.message : '连接失败，请检查两台设备的网络。'; }
  class ApiError extends Error { constructor(message, status, code) { super(message); this.status = status; this.code = code; } }
  function requestId(){return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,ch=>{const n=Math.floor(Math.random()*16);return(ch==='x'?n:(n&3)|8).toString(16);});}
  function trace(task,event,values){
    if(!task.traceStart)task.traceStart=performance.now();
    const entry=Object.assign({atMs:Math.round(performance.now()-task.traceStart),event},values||{});
    Object.keys(entry).forEach(key=>{if(typeof entry[key]==='number')entry[key]=Math.round(entry[key]*10)/10;});
    if(!task.trace)task.trace=[];task.trace.push(entry);if(task.trace.length>96)task.trace.shift();
  }
  function diagnosticText(task){
    return JSON.stringify({schemaVersion:1,client:{platform:IOS?'iOS':/Android/i.test(navigator.userAgent)?'Android':'desktop',engine:/Firefox/i.test(navigator.userAgent)?'Firefox':/AppleWebKit/i.test(navigator.userAgent)&&!/(Chrome|Chromium|Edg)/i.test(navigator.userAgent)?'WebKit':'Chromium',uploadChannel},task:{size:task.size,sentBytes:task.loaded||0,restartOnly:true},events:task.trace||[],probes:task.probes||[],server:task.serverDiagnostic||null},null,2);
  }
  function captureDiagnostics(task,error){
    trace(task,'failed',{status:error.status||0,code:error.code||'',name:error.name||'Error'});task.serverDiagnostic=null;
    // Fetch ahead of the user's copy gesture. Plain local HTTP cannot rely on
    // asynchronous clipboard access retaining iOS user activation.
    api('/api/diagnostics',{timeout:4500}).then(value=>{task.serverDiagnostic=value;},()=>{trace(task,'diagnostics-unavailable');});
  }
  async function copyDiagnostic(task,item){
    showDiagnostic(task,item);
    const text=diagnosticText(task);item.diagnostic.value=text;item.diagnosticNote.textContent='';
    if(window.isSecureContext&&navigator.clipboard&&typeof navigator.clipboard.writeText==='function')try{await navigator.clipboard.writeText(text);item.diagnosticNote.textContent='已复制';return;}catch(_){}
    item.diagnostic.focus();item.diagnostic.select();item.diagnostic.setSelectionRange(0,text.length);
    let copied=false;try{copied=document.execCommand('copy');}catch(_){}
    item.diagnosticNote.textContent=copied?'已复制':'已选中文字，请长按并选择“复制”。';
  }
  function showDiagnostic(task,item){
    if(!item.diagnostic){item.diagnostic=node('textarea','received-text');item.diagnostic.readOnly=true;item.diagnostic.rows=8;item.diagnostic.setAttribute('aria-label','传输诊断');item.diagnosticNote=node('p','copy-note');item.row.append(item.diagnostic,item.diagnosticNote);}
    item.diagnostic.hidden=false;item.diagnosticNote.hidden=false;item.diagnostic.value=diagnosticText(task);
  }
  async function diagnose(task,item,control){
    control.disabled=true;control.textContent='诊断中';showDiagnostic(task,item);task.probes=[];
    const bytes=new Uint8Array(512*1024);for(let i=0;i<bytes.length;i++)bytes[i]=(i*31+7)&255;
    const expected=new FileFlashSHA256().update(bytes).hex(),deadline=performance.now()+12000;
    for(const mode of ['xhr-post-blob','fetch-put-buffer','fetch-post-buffer','fetch-post-blob']){
      if(!session||performance.now()>=deadline)break;
      const id=requestId(),start=performance.now(),timeout=Math.max(1,Math.min(3000,deadline-start));let status=0,bodySent=false;
      const result={mode,requestId:id,bytes:bytes.length};
      try{
        const headers={'Authorization':'Bearer '+session,'Content-Type':'application/octet-stream','X-Request-ID':id};let value;
        if(mode==='xhr-post-blob')value=await new Promise((resolve,reject)=>{
          const xhr=new XMLHttpRequest();xhr.open('POST','/api/diagnostics/probe');xhr.timeout=timeout;Object.keys(headers).forEach(key=>xhr.setRequestHeader(key,headers[key]));xhr.upload.onload=()=>{bodySent=true;};
          xhr.onload=()=>{status=xhr.status;try{const data=JSON.parse(xhr.responseText);status>=200&&status<300?resolve(data):reject(new Error('http'));}catch(error){reject(error);}};
          xhr.onerror=()=>reject(new Error('network'));xhr.ontimeout=()=>reject(new Error('timeout'));xhr.onabort=()=>reject(new Error('abort'));xhr.send(new Blob([bytes],{type:'application/octet-stream'}));
        });
        else{
          const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout);
          try{const response=await fetch('/api/diagnostics/probe',{method:mode.includes('-put-')?'PUT':'POST',headers,body:mode.endsWith('-blob')?new Blob([bytes],{type:'application/octet-stream'}):bytes.buffer.slice(0),signal:controller.signal});status=response.status;if(!response.ok)throw new Error('http');value=await response.json();}finally{clearTimeout(timer);}
        }
        result.valid=value.length===bytes.length&&value.sha256===expected;if(!result.valid)result.error='checksum';
      }catch(error){result.valid=false;result.error=error.name==='AbortError'?'timeout':['network','timeout','abort','http'].includes(error.message)?error.message:'response';}
      Object.assign(result,{status,elapsedMs:Math.round(performance.now()-start),...(mode==='xhr-post-blob'?{bodySent}:{})});task.probes.push(result);showDiagnostic(task,item);
    }
    const xhrFailure=task.probes.find(value=>value.mode==='xhr-post-blob'&&!value.valid);
    const transportFailed=xhrFailure&&((xhrFailure.status===0&&['network','timeout','abort'].includes(xhrFailure.error))||(xhrFailure.status>=200&&xhrFailure.status<300&&xhrFailure.error==='checksum'));
    if(transportFailed||task.transportFailureMode===DEFAULT_UPLOAD_CHANNEL){
      if(task.probes.some(value=>value.mode.startsWith('fetch-post-')&&value.valid)){uploadChannel='fetch-post-file';trace(task,'transport-selected',{mode:uploadChannel});}
    }
    try{task.serverDiagnostic=await api('/api/diagnostics',{timeout:2000});}catch(_){trace(task,'diagnostics-unavailable');}
    showDiagnostic(task,item);control.disabled=false;control.textContent='诊断';
    if(!item.copyDiagnosticButton){item.copyDiagnosticButton=button('复制诊断','quiet small',()=>copyDiagnostic(task,item),'copy');item.actions.append(item.copyDiagnosticButton);}
    toast('诊断完成');
  }
  async function api(path, options) {
    const opts = Object.assign({ method:'GET' }, options || {});
    opts.headers = Object.assign({}, opts.headers || {});
    if (session) opts.headers.Authorization = 'Bearer ' + session;
    if (opts.json !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(opts.json); delete opts.json; }
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), opts.timeout || 45000);
    const outer = opts.signal;
    const abort = () => controller.abort();
    if (outer) { if (outer.aborted) controller.abort(); else outer.addEventListener('abort', abort, { once:true }); }
    opts.signal = controller.signal; delete opts.timeout;
    try {
      let response;
      try { response = await fetch(path, opts); } catch (err) { if (outer && outer.aborted) throw err; throw new Error('连接中断或请求超时。请保持页面在前台，并检查 Android 服务与 Wi-Fi。'); }
      let data;
      try { data = await response.json(); } catch (_) { if (!response.ok) data = {}; else throw new Error('设备返回了无法识别的数据，请重新连接。'); }
      if (!response.ok) { if (response.status === 401 && path !== '/api/pair') expired(); throw new ApiError(data.error || '操作失败（' + response.status + '）', response.status, data.code); }
      return data;
    } finally { clearTimeout(timeout); if (outer) outer.removeEventListener('abort', abort); }
  }
  function uploadHeaders(t) { return t.resumeKey ? { 'X-Upload-Key':t.resumeKey } : {}; }
  function setConnection(next, reason) {
    connection = next;
    const labels = { connected:'已连接', disconnected:'连接中断', pairing:'正在连接', expired:'配对已失效', unpaired:'未连接' };
    $('connection-label').textContent = labels[next] || '未连接';
    $('connection-dot').className = 'dot ' + next;
    $('workspace').hidden = !session;
    $('pair-panel').hidden = !!session;
    $('disconnect-button').hidden = !session;
    $('diagnostic-button').hidden = !session;
    $('server-address').textContent = location.origin;
    $('server-name').textContent = server.serverName || '连接详情';
    message($('connection-error'), reason || (next === 'disconnected' && session ? '连接已中断。请确认 Android 服务已启动，两台设备连接同一 Wi-Fi；恢复后会自动重连。' : ''));
    $('choose-files').disabled = next !== 'connected';
    $('send-text').disabled = next !== 'connected' || !$('text-input').value.length || textSending;
  }
  function expired() {
    session = null; clientId = null; store(SESSION_KEY, null);
    if(current) { current.interrupted = true; if(current.abort) current.abort(); }
    clearInterval(poll); poll = null;
    setConnection('expired');
    message($('pair-error'), '配对已失效。请重新扫描 Android 上的二维码。');
  }
  async function pair(token) {
    const name = $('device-name').value.trim() || '浏览器';
    $('pair-button').disabled = true; $('pair-button').textContent = '正在连接';
    message($('pair-error'), ''); setConnection('pairing');
    try {
      const result = await api('/api/pair', { method:'POST',json:{token:token.trim(),name} });
      if(!result.session) throw new Error('设备未返回配对凭据。');
      session = result.session; clientId = result.clientId; store(SESSION_KEY, { session,clientId,name });
      uploadChannel=DEFAULT_UPLOAD_CHANNEL;
      $('pair-token').value = ''; await refresh(true); startPolling();
    } catch(err) { setConnection('unpaired'); message($('pair-error'), errorText(err)); }
    finally { $('pair-button').disabled = false; $('pair-button').textContent = '连接'; }
  }
  async function refresh(force) {
    if(!session || refreshing) return; refreshing = true;
    try {
      const state = await api('/api/state', { timeout:12000 });
      server = Object.assign({files:[],texts:[],uploads:[],downloads:[]},state);
      if(!Array.isArray(server.files)||!Array.isArray(server.texts)||!Array.isArray(server.downloads)) throw new Error('设备返回了无效的传输列表。');
      setConnection('connected'); renderFiles(); renderTexts(); renderTasks(); pump();
      if(force) toast('已连接');
    } catch(err) { if(session) setConnection('disconnected'); if(force) toast(errorText(err),6000); }
    finally { refreshing = false; }
  }
  function startPolling() { if(!poll) poll = setInterval(() => { if(!document.hidden) refresh(false); },2500); }
  function switchTab(value) {
    tab = value;
    document.querySelectorAll('[data-tab]').forEach(el => { const active = el.dataset.tab === value; el.classList.toggle('active',active); if(active) el.setAttribute('aria-current','page'); else el.removeAttribute('aria-current'); });
    ['files','texts','tasks'].forEach(id => { $('panel-'+id).hidden = id!==value; });
    if(value === 'texts') { server.texts.forEach(t => seenTexts.add(t.id)); $('text-badge').hidden = true; }
  }
  function renderFiles() {
    const signature = JSON.stringify(server.files);
    $('file-count').textContent = server.files.length ? server.files.length + ' 个文件' : '';
    if(signature === filesSignature) return; filesSignature = signature;
    const list = $('file-list'); list.replaceChildren();
    if(!server.files.length) { list.append(node('p','empty','暂无可接收文件')); return; }
    server.files.slice().reverse().forEach(file => {
      const row=node('div','file-row'),mark=node('span','file-icon'),info=node('div','file-info');mark.append(icon('file'));
      info.append(node('div','file-name',file.name),node('div','file-meta',[bytes(file.size),file.temporary?'临时中转':null,date(file.createdAt)].filter(Boolean).join(' · ')));
      const download=button('下载','file-action',async () => {
        download.disabled=true; download.textContent='准备中';
        try {
          const ticket = await api('/api/files/'+encodeURIComponent(file.id)+'/ticket',{method:'POST',json:{}});
          const url = new URL(ticket.url,location.href);
          if(url.origin !== location.origin || !url.pathname.startsWith('/api/downloads/')) throw new Error('设备返回了无效的下载地址。');
          $('download-note').hidden=false;
          // Native navigation leaves streaming and disk saving to Safari, never builds a file Blob.
          const link = document.createElement('a');link.href=url.href;link.download=file.name;link.rel='noreferrer';document.body.append(link);link.click();link.remove();
          toast('下载已交给浏览器，请查看下载管理器。',5000);
          setTimeout(() => refresh(false),1000);
        } catch(err) { toast(errorText(err),6000); }
        finally { download.disabled=false; download.replaceChildren(icon('download'),document.createTextNode('下载')); }
      },'download');
      row.append(mark,info,download);list.append(row);
    });
  }
  function renderTexts() {
    const signature=JSON.stringify(server.texts);
    $('text-total').textContent=server.texts.length?server.texts.length+' 条':'';
    const unseen=server.texts.filter(t=>!seenTexts.has(t.id)).length;
    $('text-badge').textContent=unseen>99?'99+':unseen; $('text-badge').hidden=!unseen||tab==='texts';
    if(tab==='texts') server.texts.forEach(t=>seenTexts.add(t.id));
    if(signature===textsSignature) return; textsSignature=signature;
    const list=$('text-list'),alive=new Set(),ordered=[];
    if(!server.texts.length){list.replaceChildren(node('p','empty','暂无文字'));textRows.clear();return;}
    const empty=list.querySelector('.empty');if(empty)empty.remove();
    server.texts.slice().reverse().forEach(text=>{
      alive.add(text.id);
      if(textRows.has(text.id)){ordered.push(textRows.get(text.id));return;}
      const card=node('article','text-card'),head=node('div','text-card-head'),area=node('textarea','received-text'),note=node('p','copy-note');
      area.readOnly=true;area.value=text.text;area.rows=Math.min(8,Math.max(3,String(text.text).split('\n').length));area.setAttribute('aria-label','来自 '+(text.sender||'设备')+' 的文字');
      head.append(node('span','file-meta',(text.sender||'设备')+' · '+date(text.createdAt)));
      head.append(button('复制','quiet small',async()=>{
        note.textContent='';
        if(window.isSecureContext&&navigator.clipboard&&typeof navigator.clipboard.writeText==='function') {
          try{await navigator.clipboard.writeText(area.value);note.textContent='已复制';return;}catch(_){}
        }
        area.focus();area.select();area.setSelectionRange(0,area.value.length);
        let copied=false;try{copied=document.execCommand('copy');}catch(_){}
        note.textContent=copied?'已复制':'已选中文字，请长按并选择“复制”。';
      },'copy'));
      card.append(head,area,note);textRows.set(text.id,card);ordered.push(card);
    });
    textRows.forEach((card,id)=>{if(!alive.has(id)){card.remove();textRows.delete(id);}});
    ordered.forEach((card,index)=>{if(list.children[index]!==card)list.insertBefore(card,list.children[index]||null);});
  }
  const statusLabels = {queued:'排队中',uploading:'发送中',cancelling:'取消中',complete:'已完成',failed:'失败',cancelled:'已取消'};
  function renderTaskRow(task, remote) {
    const key = remote ? 'download-'+task.id : task.key;
    let item=taskRows.get(key);
    if(!item){
      const row=node('article','task-row'),top=node('div','task-top'),info=node('div','file-info'),name=node('div','file-name'),meta=node('div','file-meta'),state=node('span','task-state');
      info.append(name,meta);top.append(info,state);
      const progress=document.createElement('progress');progress.max=1;progress.value=0;progress.setAttribute('aria-label','传输进度');
      const detail=node('div','task-detail'),amount=node('span'),speed=node('span'),error=node('div','task-error'),actions=node('div','task-actions');detail.append(amount,speed);row.append(top,progress,detail,error,actions);
      item={row,name,meta,state,progress,amount,speed,error,actions,actionKey:''};taskRows.set(key,item);
    }
    const status=task.status||'uploading';
    if(item.diagnostic){item.diagnostic.hidden=status!=='failed';item.diagnosticNote.hidden=status!=='failed';}
    item.name.textContent=task.name;item.meta.textContent=(remote?'接收 · ':'发送 · ')+bytes(task.size);
    item.state.textContent=remote?(status==='complete'?'传输完成':status==='failed'?'下载中断':status==='cancelled'?'已取消':'浏览器下载中'):(statusLabels[status]||status);
    item.state.className='task-state '+status;
    const loaded=remote?(task.transferred||0):status==='complete'?task.size:(task.loaded||0),total=task.size;
    item.progress.value=total?Math.min(1,loaded/total):(status==='complete'?1:0);
    item.progress.setAttribute('aria-valuetext',bytes(loaded)+' / '+bytes(total));
    item.amount.textContent=(!remote&&status!=='complete'?'已发送 ':'')+bytes(loaded)+' / '+bytes(total);
    item.speed.textContent=!remote&&status==='uploading'?(task.phase|| (task.speed>0?bytes(task.speed)+'/s':'')):'';
    const error=remote?(status==='complete'?'保存结果请查看 Safari 下载管理器。':task.error||''):task.error||'';
    item.error.textContent=error;item.error.hidden=!error;item.error.className=remote&&status==='complete'?'task-note':'task-error';
    const actionKey=status+':'+!!task.file+':'+remote;
    if(item.actionKey!==actionKey){
      item.actionKey=actionKey;item.actions.replaceChildren();
      if(!remote && status !== 'cancelling'){
        if(status==='failed') item.actions.append(button(task.file?'重试':'选择文件','quiet small',()=>{
          if(task.file){task.status='queued';task.error='';persistTasks();renderTasks();pump();}
          else chooseFile(task);
        },'refresh'));
        if(status==='failed'){const diagnostic=button('诊断','quiet small',()=>diagnose(task,item,diagnostic));item.actions.append(diagnostic);item.copyDiagnosticButton=button('复制诊断','quiet small',()=>copyDiagnostic(task,item),'copy');item.actions.append(item.copyDiagnosticButton);}
        if(!['complete','cancelled'].includes(status)) item.actions.append(button('取消','quiet small danger',()=>cancelTask(task)));
      }
    }
    return item.row;
  }
  function renderTasks() {
    const list=$('task-list'),active=tasks.filter(t=>['queued','uploading'].includes(t.status)).length;
    $('task-badge').textContent=active;$('task-badge').hidden=!active;
    $('foreground-note').hidden=!tasks.some(t=>!['complete','cancelled'].includes(t.status));
    $('queue-summary').textContent=active?active+' 个任务进行中':'支持多选';
    const downloads=(server.downloads||[]).filter(d=>!clientId||d.clientId===clientId);
    const alive=new Set(),ordered=[];
    tasks.slice().reverse().forEach(task=>{alive.add(task.key);ordered.push(renderTaskRow(task,false));});
    downloads.slice().reverse().forEach(task=>{alive.add('download-'+task.id);ordered.push(renderTaskRow(task,true));});
    taskRows.forEach((item,key)=>{if(!alive.has(key)){item.row.remove();taskRows.delete(key);}});
    if(!ordered.length){if(!list.querySelector('.empty'))list.replaceChildren(node('p','empty','暂无传输任务'));}
    else{const empty=list.querySelector('.empty');if(empty)empty.remove();ordered.forEach((row,index)=>{if(list.children[index]!==row)list.insertBefore(row,list.children[index]||null);});}
  }
  function scheduleRender() { if(renderPending)return;renderPending=true;setTimeout(()=>{renderPending=false;renderTasks();},120); }
  function chooseFile(task) { targetSelection=task||null;$('file-input').multiple=!task;$('file-input').value='';$('file-input').click(); }
  function addFiles(files) {
    if(!session||connection!=='connected'){toast('请先连接 Android。');return;}
    if(targetSelection){const task=targetSelection;targetSelection=null;if(!files.length)return;task.file=files[0];task.name=task.file.name;task.size=task.file.size;task.mime=task.file.type||'application/octet-stream';task.offset=0;task.loaded=0;task.status='queued';task.error='';}
    else Array.from(files).forEach(file=>{if(!Number.isSafeInteger(file.size)){toast('此文件的大小超过浏览器可安全处理的范围。',6000);return;}tasks.push({key:localId(),name:file.name,size:file.size,mime:file.type||'application/octet-stream',file,offset:0,loaded:0,hashBytes:0,speed:0,status:'queued',createdAt:Date.now()});});
    persistTasks();renderTasks();switchTab('tasks');pump();
  }
  function stopCheck(task) { if(task.cancelRequested||task.interrupted||!session){const error=new Error('操作已中断，需重新发送。');error.name='AbortError';throw error;} }
  async function taskApi(task,path,options){
    const controller=new AbortController(),abort=()=>controller.abort(),id=requestId(),start=performance.now(),method=options&&options.method||'GET';task.abort=abort;
    const opts=Object.assign({},options||{},{signal:controller.signal});opts.headers=Object.assign({},opts.headers,{'X-Request-ID':id});trace(task,'request',{requestId:id,route:'upload',method});
    try{const result=await api(path,opts);trace(task,'response',{requestId:id,route:'upload',method,elapsedMs:performance.now()-start,status:result.status});return result;}
    catch(error){trace(task,'request-error',{requestId:id,route:'upload',method,elapsedMs:performance.now()-start,status:error.status||0});throw error;}
    finally{if(task.abort===abort)task.abort=null;}
  }
  async function cleanTemporary(task){
    if(!task.id)return;
    try{await api('/api/uploads/'+encodeURIComponent(task.id),{method:'DELETE',headers:uploadHeaders(task),timeout:8000});}
    catch(error){if(error.status!==404&&error.status!==410){trace(task,'cleanup-unconfirmed',{status:error.status||0});throw error;}}
    trace(task,'temporary-removed');task.id=null;delete task.resumeKey;task.offset=0;task.loaded=0;persistTasks();
  }
  function streamHeaders(task,id){return Object.assign(uploadHeaders(task),{'Authorization':'Bearer '+session,'Content-Type':'application/octet-stream','X-Request-ID':id});}
  function sendFile(task){return uploadChannel==='fetch-post-file'?sendFetchFile(task):sendXhrFile(task);}
  function sendXhrFile(task){
    return new Promise((resolve,reject)=>{
      const xhr=new XMLHttpRequest(),file=task.file,id=requestId(),start=performance.now();let timer=null,settled=false,timedOut=false;task.transportFailureMode=null;
      const arm=()=>{clearTimeout(timer);timer=setTimeout(()=>{timedOut=true;xhr.abort();},120000);};
      const done=(fn,value)=>{if(settled)return;settled=true;clearTimeout(timer);task.abort=null;trace(task,'stream-response',{requestId:id,mode:'xhr-post-file',status:xhr.status,readyState:xhr.readyState,elapsedMs:performance.now()-start,expectedBytes:file.size,sentBytes:task.loaded||0,timedOut});fn(value);};
      xhr.open('POST','/api/uploads/'+encodeURIComponent(task.id)+'/stream');xhr.timeout=0;const headers=streamHeaders(task,id);Object.keys(headers).forEach(key=>xhr.setRequestHeader(key,headers[key]));
      xhr.upload.onprogress=event=>{arm();task.loaded=Math.min(file.size,event.loaded);task.speed=event.loaded/Math.max(.001,(performance.now()-start)/1000);task.phase=task.speed>0?bytes(task.speed)+'/s':'';scheduleRender();};
      xhr.upload.onload=event=>{arm();if(event.lengthComputable)task.loaded=Math.min(file.size,event.loaded);task.phase='等待完成';trace(task,'stream-body-sent',{requestId:id,expectedBytes:file.size,reportedBytes:event.loaded,elapsedMs:performance.now()-start});scheduleRender();};
      xhr.onload=()=>{let result;try{result=JSON.parse(xhr.responseText);}catch(_){return done(reject,new Error('设备返回了无效的上传结果。'));}if(xhr.status>=200&&xhr.status<300)done(resolve,result);else{if(xhr.status===401)expired();done(reject,new ApiError(result.error||'上传失败（'+xhr.status+'）',xhr.status,result.code));}};
      xhr.onerror=()=>{task.transportFailureMode='xhr-post-file';done(reject,new Error('连接中断，请重新发送。'));};
      xhr.ontimeout=()=>{task.transportFailureMode='xhr-post-file';done(reject,new Error('上传超时，请重新发送。'));};
      xhr.onabort=()=>{if(timedOut){task.transportFailureMode='xhr-post-file';return done(reject,new Error('上传停滞超时，请重新发送。'));}const error=new Error('操作已中断，需重新发送。');error.name='AbortError';done(reject,error);};
      task.abort=()=>xhr.abort();trace(task,'stream-send',{requestId:id,mode:'xhr-post-file',method:'POST',bodyType:Object.prototype.toString.call(file),expectedBytes:file.size});arm();xhr.send(file);
    });
  }
  async function sendFetchFile(task){
    const controller=new AbortController(),file=task.file,id=requestId(),start=performance.now(),duration=Math.min(86400000,120000+Math.ceil(file.size/65536)*1000);let status=0,timedOut=false;
    const timer=setTimeout(()=>{timedOut=true;controller.abort();},duration),abort=()=>controller.abort();task.abort=abort;task.phase='发送中';trace(task,'stream-send',{requestId:id,mode:'fetch-post-file',method:'POST',bodyType:Object.prototype.toString.call(file),expectedBytes:file.size,timeoutMs:duration});
    try{
      let response;try{response=await fetch('/api/uploads/'+encodeURIComponent(task.id)+'/stream',{method:'POST',headers:streamHeaders(task,id),body:file,signal:controller.signal});}
      catch(error){if(task.cancelRequested||task.interrupted||!session)throw error;throw new Error(timedOut?'上传超时，请重新发送。':'连接中断，请重新发送。');}
      status=response.status;let result;try{result=await response.json();}catch(_){throw new Error('设备返回了无效的上传结果。');}if(!response.ok){if(status===401)expired();throw new ApiError(result.error||'上传失败（'+status+'）',status,result.code);}return result;
    }finally{clearTimeout(timer);if(task.abort===abort)task.abort=null;trace(task,'stream-response',{requestId:id,mode:'fetch-post-file',status,elapsedMs:performance.now()-start,expectedBytes:file.size,timedOut});}
  }
  async function processTask(task){
    task.cancelRequested=false;task.interrupted=false;task.speed=0;task.phase='';task.loaded=0;task.offset=0;
    stopCheck(task);await cleanTemporary(task);stopCheck(task);
    task.status='uploading';task.error='';renderTasks();
    const remote=await taskApi(task,'/api/uploads',{method:'POST',json:{name:task.name,size:task.file.size,mime:task.mime,restartOnly:true}});
    task.id=remote.id;task.resumeKey=remote.resumeKey;persistTasks();stopCheck(task);
    if(!task.id||remote.size!==task.size||remote.restartOnly!==true)throw new Error('设备未创建有效的上传任务。');
    const result=await sendFile(task);stopCheck(task);
    if(result.status!=='complete'||result.size!==task.size||result.offset!==task.size)throw new Error('接收端未完整接收文件，请重新发送。');
    task.name=result.name||task.name;task.offset=task.size;task.loaded=task.size;task.speed=0;task.error='';finishTask(task,'complete');persistTasks();refresh(false);
  }
  async function pump(){
    if(busy||!session||connection!=='connected')return;busy=true;
    try{let task;while(session&&connection==='connected'&&(task=tasks.find(value=>value.status==='queued'&&value.file))){
      current=task;let stopped;task.settled=new Promise(resolve=>{stopped=resolve;});
      try{await processTask(task);}
      catch(error){task.speed=0;if(!task.cancelRequested){task.status='failed';task.error=errorText(error);captureDiagnostics(task,error);persistTasks();renderTasks();try{await cleanTemporary(task);}catch(_){task.error+=' 临时文件清理未确认，可重试清理。';}}}
      finally{task.abort=null;persistTasks();renderTasks();current=null;stopped();delete task.settled;}
    }}finally{busy=false;}
  }
  function interruptTask(task){task.interrupted=true;if(task.abort)task.abort();}
  async function cancelTask(task){
    task.cancelRequested=true;if(task.abort)task.abort();task.status='cancelling';task.error='';persistTasks();renderTasks();
    try{if(task.settled)await task.settled;await cleanTemporary(task);task.error='';finishTask(task,'cancelled');}
    catch(error){task.status='failed';task.error='未能清理临时文件：'+errorText(error);captureDiagnostics(task,error);}
    finally{persistTasks();renderTasks();}
  }
  $('pair-form').addEventListener('submit',event=>{event.preventDefault();pair($('pair-token').value);});
  $('connection-button').addEventListener('click',()=>{const details=$('connection-details');details.hidden=!details.hidden;$('connection-button').setAttribute('aria-expanded',String(!details.hidden));});
  $('refresh-button').addEventListener('click',()=>refresh(true));
  let connectionDiagnostic=null;
  $('diagnostic-button').addEventListener('click',async()=>{
    if(!connectionDiagnostic){connectionDiagnostic={row:$('connection-details'),actions:node('div','task-actions')};$('connection-details').append(connectionDiagnostic.actions);}
    const task=tasks.slice().reverse().find(value=>value.status==='failed')||{size:0,offset:0,trace:[]};
    await diagnose(task,connectionDiagnostic,$('diagnostic-button'));
  });
  $('disconnect-button').addEventListener('click',async()=>{
    const oldSession=session;
    if(current)interruptTask(current);
    tasks.forEach(t=>{if(t.status==='queued'){t.status='failed';t.error='重新连接后重传。';}});persistTasks();
    try{await api('/api/session',{method:'DELETE',timeout:8000});}catch(_){}
    if(session===oldSession){session=null;clientId=null;store(SESSION_KEY,null);clearInterval(poll);poll=null;server={files:[],texts:[],uploads:[],downloads:[]};filesSignature='';textsSignature='';setConnection('unpaired');$('connection-details').hidden=true;$('connection-button').setAttribute('aria-expanded','false');}
  });
  document.querySelectorAll('[data-tab]').forEach(el=>el.addEventListener('click',()=>switchTab(el.dataset.tab)));
  $('choose-files').addEventListener('click',()=>chooseFile(null));
  $('file-input').addEventListener('change',event=>{addFiles(event.target.files);event.target.value='';});
  $('clear-tasks').addEventListener('click',()=>{tasks=tasks.filter(t=>!['complete','cancelled'].includes(t.status));persistTasks();renderTasks();});
  $('text-input').addEventListener('input',()=>{$('text-count').textContent=$('text-input').value.length+' 字符';$('send-text').disabled=connection!=='connected'||!$('text-input').value.length||textSending;});
  $('text-form').addEventListener('submit',async event=>{
    event.preventDefault();const content=$('text-input').value;if(!content.length||textSending)return;
    textSending=true;$('send-text').disabled=true;message($('text-error'),'');
    try{await api('/api/texts',{method:'POST',json:{text:content}});if($('text-input').value===content){$('text-input').value='';$('text-count').textContent='0 字符';}toast('已发送');await refresh(false);}
    catch(err){message($('text-error'),errorText(err));}
    finally{textSending=false;$('send-text').disabled=connection!=='connected'||!$('text-input').value.length;}
  });
  document.addEventListener('visibilitychange',()=>{if(!document.hidden&&session)refresh(false);});
  window.addEventListener('online',()=>{if(session)refresh(false);});
  window.addEventListener('offline',()=>{if(session)setConnection('disconnected');});
  window.addEventListener('pagehide',()=>{if(current)interruptTask(current);persistTasks();});
  window.addEventListener('beforeunload',event=>{if(tasks.some(t=>t.status==='uploading')){event.preventDefault();event.returnValue='';}});
  $('device-name').value=(saved&&saved.name)||(/iPhone/i.test(navigator.userAgent)?'iPhone':/iPad/i.test(navigator.userAgent)?'iPad':/Android/i.test(navigator.userAgent)?'Android':'浏览器');
  persistTasks();renderTasks();$('send-text').disabled=true;
  if(pendingHashToken){$('pair-token').value=pendingHashToken;session=null;clientId=null;store(SESSION_KEY,null);pair(pendingHashToken);}
  else if(session){setConnection('disconnected','正在恢复连接…');refresh(false);startPolling();}
  else setConnection('unpaired');
})();
