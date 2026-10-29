// Exercise the shipped client with controlled intersection, network and lifecycle events.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function harness() {
  class Element {
    constructor() { this.value=''; this.children=[]; this.handlers={}; this.classes=new Set(); this.classList={toggle:(key,on)=>on?this.classes.add(key):this.classes.delete(key)}; }
    addEventListener(name,cb) { this.handlers[name]=cb; }
    setAttribute() {}
    removeAttribute() {}
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children=children; }
    querySelector() { return null; }
  }
  const elements=new Map(), requests=[], urls=[], revoked=[], events={}, timers=new Map();
  const get=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  let observer, timerId=0;
  const context={
    navigator:{userAgent:'Browser',platform:'test',maxTouchPoints:0},
    location:{hash:'',pathname:'/',search:'',origin:'http://fixture'}, history:{replaceState(){}}, performance:{now:()=>0},
    document:{body:new Element(),hidden:false,getElementById:get,querySelectorAll:()=>[],createElement:()=>new Element(),createElementNS:()=>new Element(),createTextNode:()=>new Element(),addEventListener:(name,cb)=>events[name]=cb},
    window:{addEventListener:(name,cb)=>events[name]=cb},
    localStorage:{getItem:()=>null,setItem(){},removeItem(){}}, URLSearchParams,AbortController,Blob,
    URL:{createObjectURL:blob=>{const value='blob:'+urls.length;urls.push({value,blob});return value;},revokeObjectURL:value=>revoked.push(value)},
    IntersectionObserver:class { constructor(cb){this.cb=cb;this.observed=new Set();observer=this;} observe(el){this.observed.add(el);} disconnect(){this.observed.clear();} },
    setTimeout:(cb)=>{timers.set(++timerId,cb);return timerId;},clearTimeout:id=>timers.delete(id),setInterval:()=>1,clearInterval(){},
    fetch:(url,options)=>new Promise(resolve=>requests.push({url,options,resolve}))
  };
  vm.createContext(context);
  let source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web/app.js'),'utf8');
  source=source.replace(/\}\)\(\);\s*$/, `globalThis.fixture={
    setup(value){session=value;setConnection('connected');},
    fileKind,filePreview,switchTab,clearPreviews,
    disconnected(){setConnection('disconnected');},
    state:()=>connection,count:()=>previewActive
  };})();`);
  vm.runInContext(source,context);
  const visible=(mark,on=true)=>observer.cb([{target:mark,isIntersecting:on}]);
  const respond=(request,{status=200,bytes=12,type='image/jpeg',length=bytes}={})=>{
    let read=false;
    request.resolve({status,headers:{get:name=>name==='Content-Type'?type:String(length)},body:{getReader:()=>({read:async()=>read?{done:true}:(read=true,{done:false,value:new Uint8Array(bytes)}),cancel:async()=>{},releaseLock(){}})}});
  };
  return {fixture:context.fixture,requests,urls,revoked,visible,respond,events,document:context.document,observer:()=>observer,timers};
}
const tick=async()=>{for(let i=0;i<4;i++)await new Promise(resolve=>setImmediate(resolve));};
const file=(id,name,mime='application/octet-stream')=>({id,name,mime});

(async()=>{
  {
    const h=harness();h.fixture.setup('paired');
    const core=fs.readFileSync(path.join(__dirname,'../core/src/main/java/app/fileflash/core/FileKind.java'),'utf8');
    for(const [set,kind] of [['IMAGES','image'],['VIDEOS','video'],['DOCUMENTS','document'],['AUDIO_FILES','audio']]) {
      const extensions=core.match(new RegExp(set+' = extensions\\(([^;]+)\\)'))[1].match(/"([^"]+)"/g).map(s=>s.slice(1,-1));
      for(const extension of extensions)assert.equal(h.fixture.fileKind(file('x','FILE.'+extension.toUpperCase())),kind);
    }
    assert.equal(h.fixture.fileKind(file('x','jpg')),'other');
    assert.equal(h.fixture.fileKind(file('x','x','application/activity+json')),'document');
    for(const [name,asset] of [['notes.xml','documents-folder'],['music.ncm','audio-eighth-note'],['app.apk','unknown-file']]) {
      const mark=h.fixture.filePreview(file(name,name));
      assert.equal(mark.children[0].src,'/file-icons/'+asset+'.svg');
    }
    assert.equal(h.requests.length,0,'Category icons never download originals or thumbnails');
  }
  {
    const h=harness();h.fixture.setup('paired');
    const first=h.fixture.filePreview(file('one','photo.HEIC')),second=h.fixture.filePreview(file('two','video.MOV'));
    assert.equal(h.requests.length,0,'Offscreen media must stay unloaded');
    h.visible(first);h.visible(second);
    assert.equal(h.requests.length,1,'Thumbnail concurrency is one');
    const request=h.requests.shift();assert.equal(request.url,'/api/files/one/thumbnail');
    assert.equal(request.options.headers.Authorization,'Bearer paired');assert.equal(request.options.cache,'no-store');
    h.respond(request);await tick();assert.equal(h.urls.length,1);assert.equal(h.requests.length,1);
    h.visible(first,false);assert.deepEqual(h.revoked,['blob:0']);
    const secondRequest=h.requests.shift();h.fixture.switchTab('texts');assert.equal(secondRequest.options.signal.aborted,true);
    h.respond(secondRequest);await tick();assert.equal(h.urls.length,1,'Hidden-tab response cannot recreate thumbnail');
  }
  {
    const h=harness();h.fixture.setup('old');const mark=h.fixture.filePreview(file('one','photo.jpg'));
    h.visible(mark);const request=h.requests.shift();h.fixture.setup('new');
    assert.equal(request.options.signal.aborted,true);h.respond(request);await tick();assert.equal(h.urls.length,0);
    assert.equal(h.fixture.state(),'connected','Preview errors do not mutate the connection state');
  }
  {
    for(const response of [{bytes:262145,length:0},{type:'text/html'},{status:404},{status:204}]) {
      const h=harness();h.fixture.setup('paired');const mark=h.fixture.filePreview(file('one','photo.jpg'));
      h.visible(mark);h.respond(h.requests.shift(),response);await tick();
      assert.equal(h.urls.length,0);assert.equal(h.requests.length,0,'Unavailable previews must not loop');
      assert.equal(h.fixture.state(),'connected');
    }
  }
  {
    const h=harness();h.fixture.setup('paired');const marks=[];
    for(let i=0;i<20;i++){const mark=h.fixture.filePreview(file(String(i),'photo.jpg'));marks.push(mark);h.visible(mark);}
    for(let i=0;i<16;i++){h.respond(h.requests.shift());await tick();}
    assert.equal(h.urls.length,16);assert.equal(h.requests.length,0,'At most 16 visible image blobs');
    h.visible(marks[0],false);assert.equal(h.requests.length,1,'Freeing one slot resumes the visible queue');
    h.respond(h.requests.shift());await tick();h.fixture.clearPreviews();
    assert.equal(h.revoked.length,17,'List reset releases every object URL');
  }
  {
    const h=harness();h.fixture.setup('paired');const mark=h.fixture.filePreview(file('one','photo.jpg'));h.visible(mark);
    for(let attempt=0;attempt<2;attempt++) {
      h.respond(h.requests.shift(),{status:429});await tick();assert.equal(h.requests.length,0);
      const [id,retry]=Array.from(h.timers.entries())[0];h.timers.delete(id);retry();assert.equal(h.requests.length,1);
    }
    h.respond(h.requests.shift(),{status:429});await tick();assert.equal(h.requests.length,0);assert.equal(h.timers.size,0,'Busy responses retry twice, then stop');
  }
  console.log('6 browser thumbnail lifecycle groups passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
