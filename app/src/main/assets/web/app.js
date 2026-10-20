/* FileFlash browser client. No remote assets, framework, or secure-context API required. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const CHUNK = 2 * 1024 * 1024;
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
    key: t.key || localId(), status: ['complete', 'cancelled'].includes(t.status) ? t.status : 'paused',
    file: null, speed: 0, loaded: t.offset || 0, hashBytes: 0,
    error: ['complete', 'cancelled'].includes(t.status) ? t.error : '重新选择原文件以继续。'
  }));
  let busy = false, current = null, refreshing = false, poll = null, targetSelection = null, textSending = false;
  let filesSignature = '', textsSignature = '', storageWarned = false, renderPending = false;
  let toastTimer, seenTexts = new Set();
  const taskRows = new Map(), textRows = new Map();
  function readStore(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) || fallback; } catch (_) { return fallback; } }
  function store(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (_) {
    if (!storageWarned) { storageWarned = true; toast('浏览器无法保存连接与进度；关闭页面后需要重新配对。', 7000); }
  } }
  function validStoredTask(t) { return t && typeof t.name === 'string' && Number.isSafeInteger(t.size) && t.size >= 0 && (!t.id || /^[A-Za-z0-9_-]+$/.test(t.id)); }
  function terminalTask(task) { return ['complete', 'cancelled'].includes(task.status); }
  function finishTask(task, status) { task.status = status; task.file = null; delete task.resumeKey; task.finishedAt = Date.now(); }
  function trimHistory() {
    // History is metadata only. Keep every resumable task, while completed rows and their secrets are disposable.
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
    store(TASKS_KEY, tasks.map(t => ({ key:t.key,id:t.id,resumeKey:t.resumeKey,name:t.name,size:t.size,mime:t.mime,sha256:t.sha256,offset:t.offset||0,status:t.status,createdAt:t.createdAt,finishedAt:t.finishedAt,error:t.error||'' })));
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
    $('server-address').textContent = location.origin;
    $('server-name').textContent = server.serverName || '连接详情';
    message($('connection-error'), reason || (next === 'disconnected' && session ? '连接已中断。请确认 Android 服务已启动，两台设备连接同一 Wi-Fi；恢复后会自动重连。' : ''));
    $('choose-files').disabled = next !== 'connected';
    $('send-text').disabled = next !== 'connected' || !$('text-input').value.length || textSending;
  }
  function expired() {
    session = null; clientId = null; store(SESSION_KEY, null);
    if(current) { current.pauseRequested = true; if(current.abort) current.abort(); }
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
      info.append(node('div','file-name',file.name),node('div','file-meta',[bytes(file.size),date(file.createdAt)].filter(Boolean).join(' · ')));
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
  const statusLabels = {queued:'排队中',hashing:'校验原文件',uploading:'发送中',verifying:'最终校验',cancelling:'取消中',complete:'已完成',failed:'失败',paused:'已暂停',cancelled:'已取消'};
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
    const status=task.status||'uploading',hashing=status==='hashing';
    item.name.textContent=task.name;item.meta.textContent=(remote?'接收 · ':'发送 · ')+bytes(task.size);
    item.state.textContent=remote?(status==='complete'?'传输完成':status==='failed'?'下载中断':status==='cancelled'?'已取消':'浏览器下载中'):(statusLabels[status]||status);
    item.state.className='task-state '+status;
    const loaded=remote?(task.transferred||0):hashing?(task.hashBytes||0):status==='complete'?task.size:Math.max(task.offset||0,task.loaded||0);
    item.progress.value=task.size?Math.min(1,loaded/task.size):(status==='complete'?1:0);
    item.progress.setAttribute('aria-valuetext',bytes(loaded)+' / '+bytes(task.size));
    item.amount.textContent=(hashing?'已校验 ':'')+bytes(loaded)+' / '+bytes(task.size);
    item.speed.textContent=!remote&&status==='uploading'&&task.speed>0?bytes(task.speed)+'/s':'';
    const error=remote?(status==='complete'?'保存结果请查看 Safari 下载管理器。':task.error||''):task.error||'';
    item.error.textContent=error;item.error.hidden=!error;item.error.className=remote&&status==='complete'?'task-note':'task-error';
    const actionKey=status+':'+!!task.file+':'+remote;
    if(item.actionKey!==actionKey){
      item.actionKey=actionKey;item.actions.replaceChildren();
      if(!remote && status !== 'cancelling'){
        if(['queued','hashing','uploading','verifying'].includes(status)) item.actions.append(button('暂停','quiet small',()=>pauseTask(task)));
        if(['failed','paused'].includes(status)) item.actions.append(button(task.file?'重试':'选择原文件','quiet small',()=>{
          if(task.file){task.status='queued';task.error='';persistTasks();renderTasks();pump();}
          else chooseFile(task);
        },'refresh'));
        if(!['complete','cancelled'].includes(status)) item.actions.append(button('取消','quiet small danger',()=>cancelTask(task)));
      }
    }
    return item.row;
  }
  function renderTasks() {
    const list=$('task-list'),active=tasks.filter(t=>['queued','hashing','uploading','verifying'].includes(t.status)).length;
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
    if(targetSelection){const task=targetSelection;targetSelection=null;if(!files.length)return;task.file=files[0];task.status='queued';task.error='';}
    else Array.from(files).forEach(file=>{if(!Number.isSafeInteger(file.size)){toast('此文件的大小超过浏览器可安全处理的范围。',6000);return;}tasks.push({key:localId(),name:file.name,size:file.size,mime:file.type||'application/octet-stream',file,offset:0,loaded:0,hashBytes:0,speed:0,status:'queued',createdAt:Date.now()});});
    persistTasks();renderTasks();switchTab('tasks');pump();
  }
  function stopCheck(task) { if(task.cancelRequested||task.pauseRequested||!session) { const err=new Error('paused');err.name='AbortError';throw err; } }
  async function digestFile(task) {
    const hash=new FileFlashSHA256();task.status='hashing';task.hashBytes=0;task.error='';renderTasks();
    // 512 KiB keeps main-thread work interruptible even on a slower phone.
    for(let offset=0;offset<task.file.size;offset+=512*1024){
      stopCheck(task);const data=await task.file.slice(offset,Math.min(offset+512*1024,task.file.size)).arrayBuffer();stopCheck(task);hash.update(data);task.hashBytes=Math.min(offset+data.byteLength,task.file.size);scheduleRender();await new Promise(resolve=>setTimeout(resolve,0));
    }
    stopCheck(task);return hash.hex();
  }
  function sendChunk(task, data, chunkHash) {
    return new Promise((resolve,reject)=>{
      const xhr=new XMLHttpRequest(),start=performance.now(),base=task.offset;let settled=false;
      const done=(fn,value)=>{if(settled)return;settled=true;task.abort=null;fn(value);};
      xhr.open('PUT','/api/uploads/'+encodeURIComponent(task.id));xhr.timeout=120000;
      xhr.setRequestHeader('Authorization','Bearer '+session);xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.setRequestHeader('X-Offset',String(base));xhr.setRequestHeader('X-Chunk-SHA256',chunkHash);
      if(task.resumeKey)xhr.setRequestHeader('X-Upload-Key',task.resumeKey);
      xhr.upload.onprogress=e=>{task.loaded=base+e.loaded;task.speed=e.loaded/Math.max(.001,(performance.now()-start)/1000);scheduleRender();};
      xhr.onload=()=>{let result;try{result=JSON.parse(xhr.responseText);}catch(_){return done(reject,new Error('设备返回了无效的分块结果。'));}if(xhr.status>=200&&xhr.status<300)done(resolve,result);else{if(xhr.status===401)expired();done(reject,new ApiError(result.error||'分块发送失败（'+xhr.status+'）',xhr.status,result.code));}};
      xhr.onerror=()=>done(reject,new Error('连接中断。恢复网络后可重试，已接收的分块会保留。'));
      xhr.ontimeout=()=>done(reject,new Error('发送超时。请保持页面在前台，恢复连接后重试。'));
      xhr.onabort=()=>{const err=new Error('paused');err.name='AbortError';done(reject,err);};
      task.abort=()=>xhr.abort();xhr.send(data);
    });
  }
  function validateOffset(value,size) { if(!Number.isSafeInteger(value)||value<0||value>size)throw new Error('设备返回了无效的文件偏移，已停止传输。');return value; }
  async function processTask(task) {
    task.pauseRequested=false;task.cancelRequested=false;task.speed=0;
    const digest=await digestFile(task);
    if(task.sha256&&task.sha256!==digest){task.file=null;throw new Error('所选文件与原文件内容不同。请重新选择原文件。');}
    if(task.size!==task.file.size){task.file=null;throw new Error('所选文件大小与原文件不同。请重新选择原文件。');}
    task.sha256=digest;
    // Content identity, never the filename alone, permits recovery after a reload.
    if(!task.id){
      const previous=tasks.find(t=>t!==task&&t.id&&t.resumeKey&&t.sha256===digest&&t.size===task.size&&!['complete','cancelled'].includes(t.status));
      if(previous){task.id=previous.id;task.resumeKey=previous.resumeKey;task.name=previous.name;task.offset=previous.offset;tasks=tasks.filter(t=>t!==previous);}
    }
    stopCheck(task);let remote;
    if(task.id){
      try{remote=await api('/api/uploads/'+encodeURIComponent(task.id),{headers:uploadHeaders(task)});}
      catch(err){if(err.status===404||err.status===410){task.id=null;task.resumeKey=null;task.offset=0;task.loaded=0;toast('断点已清理，将重新发送。',6000);}else throw err;}
    }
    if(remote&&remote.status==='complete'){if(remote.sha256&&remote.sha256!==digest)throw new Error('接收端校验值不一致。');task.name=remote.name||task.name;task.offset=task.size;task.loaded=task.size;task.error='';finishTask(task,'complete');persistTasks();return;}
    if(remote&&remote.status==='cancelled'){task.id=null;task.resumeKey=null;remote=null;}
    if(!task.id){remote=await api('/api/uploads',{method:'POST',json:{name:task.name,size:task.size,mime:task.mime,sha256:digest}});task.id=remote.id;task.resumeKey=remote.resumeKey;}
    if(!task.id||!remote)throw new Error('设备未创建传输任务。');
    if(remote.size!==task.size||(remote.sha256&&remote.sha256!==digest))throw new Error('接收端任务与原文件不一致，已停止传输。');
    task.resumeKey=remote.resumeKey||task.resumeKey;task.offset=validateOffset(remote.offset,task.size);task.loaded=task.offset;task.status='uploading';task.error='';persistTasks();renderTasks();
    while(task.offset<task.size){
      stopCheck(task);const start=task.offset,end=Math.min(start+CHUNK,task.size),data=await task.file.slice(start,end).arrayBuffer();stopCheck(task);
      if(data.byteLength!==end-start)throw new Error('无法读取完整分块，请重新选择原文件。');
      const chunkHash=new FileFlashSHA256().update(data).hex();stopCheck(task);
      const result=await sendChunk(task,data,chunkHash);stopCheck(task);
      const confirmed=validateOffset(result.offset,task.size);
      if(confirmed!==end)throw new Error('接收端确认的分块长度不一致，请重试。');
      task.offset=confirmed;task.loaded=confirmed;persistTasks();scheduleRender();
    }
    stopCheck(task);task.status='verifying';task.speed=0;renderTasks();
    const controller=new AbortController();task.abort=()=>controller.abort();
    const result=await api('/api/uploads/'+encodeURIComponent(task.id)+'/complete',{method:'POST',json:{sha256:digest},headers:uploadHeaders(task),signal:controller.signal,timeout:600000});
    task.abort=null;stopCheck(task);
    if(result.status!=='complete')throw new Error(result.error||'接收端尚未完成文件校验，请重试。');
    if(result.sha256&&result.sha256!==digest)throw new Error('接收端校验值不一致。');
    task.name=result.name||task.name;task.offset=task.size;task.loaded=task.size;task.speed=0;task.error='';finishTask(task,'complete');persistTasks();refresh(false);
  }
  async function pump() {
    if(busy||!session||connection!=='connected')return;busy=true;
    try{
      let task;
      while(session&&connection==='connected'&&(task=tasks.find(t=>t.status==='queued'&&t.file))){
        current=task;
        try{await processTask(task);}
        catch(err){task.speed=0;task.loaded=task.offset||0;if(!task.cancelRequested){task.status=task.pauseRequested||err.name==='AbortError'?'paused':'failed';task.error=task.status==='paused'?'可继续发送；关闭页面后需要重新选择原文件。':errorText(err);}}
        finally{task.abort=null;persistTasks();renderTasks();current=null;}
      }
    }finally{busy=false;}
  }
  function pauseTask(task) { task.pauseRequested=true;if(task.abort)task.abort();if(task!==current){task.status='paused';task.error='';persistTasks();renderTasks();} }
  async function cancelTask(task) {
    task.cancelRequested=true;if(task.abort)task.abort();task.status='cancelling';task.loaded=task.offset||0;task.error='';persistTasks();renderTasks();
    try{
      if(task.id){if(!session)throw new Error('请重新连接后取消。');await api('/api/uploads/'+encodeURIComponent(task.id),{method:'DELETE',headers:uploadHeaders(task)});}
      task.error='';finishTask(task,'cancelled');
    }catch(err){task.status='failed';task.error='未能在 Android 上取消：'+errorText(err);}
    finally{persistTasks();renderTasks();}
  }
  $('pair-form').addEventListener('submit',event=>{event.preventDefault();pair($('pair-token').value);});
  $('connection-button').addEventListener('click',()=>{const details=$('connection-details');details.hidden=!details.hidden;$('connection-button').setAttribute('aria-expanded',String(!details.hidden));});
  $('refresh-button').addEventListener('click',()=>refresh(true));
  $('disconnect-button').addEventListener('click',async()=>{
    const oldSession=session;
    if(current)pauseTask(current);
    tasks.forEach(t=>{if(t.status==='queued'){t.status='paused';t.error='重新连接后可继续。';}});persistTasks();
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
  window.addEventListener('pagehide',()=>{if(current){current.pauseRequested=true;if(current.abort)current.abort();}persistTasks();});
  window.addEventListener('beforeunload',event=>{if(tasks.some(t=>['hashing','uploading','verifying'].includes(t.status))){event.preventDefault();event.returnValue='';}});
  $('device-name').value=(saved&&saved.name)||(/iPhone/i.test(navigator.userAgent)?'iPhone':/iPad/i.test(navigator.userAgent)?'iPad':/Android/i.test(navigator.userAgent)?'Android':'浏览器');
  persistTasks();renderTasks();$('send-text').disabled=true;
  if(pendingHashToken){$('pair-token').value=pendingHashToken;session=null;clientId=null;store(SESSION_KEY,null);pair(pendingHashToken);}
  else if(session){setConnection('disconnected','正在恢复连接…');refresh(false);startPolling();}
  else setConnection('unpaired');
})();
