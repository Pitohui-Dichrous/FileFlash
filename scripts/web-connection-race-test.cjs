// Execute the shipped browser client with deterministic HTTP completion ordering.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function harness() {
  class Element {
    constructor() { this.value=''; this.children=[]; this.handlers={}; this.classes=new Set(); this.classList={toggle:(name,on)=>on?this.classes.add(name):this.classes.delete(name)}; }
    addEventListener(name, callback) { this.handlers[name]=callback; }
    setAttribute() {}
    removeAttribute() {}
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children=children; }
    querySelector() { return null; }
  }
  const elements=new Map(), requests=[], storage=new Map(), body=new Element();
  const get=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  const context={
    navigator:{userAgent:'Browser',platform:'test',maxTouchPoints:0},
    location:{hash:'',pathname:'/',search:'',origin:'http://fixture'},
    history:{replaceState(){}}, performance:{now:()=>0},
    document:{body,getElementById:get,querySelectorAll:()=>[],createElement:()=>new Element(),addEventListener(){}},
    window:{addEventListener(){}},
    localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
    URLSearchParams,AbortController,setTimeout:()=>1,clearTimeout(){},setInterval:()=>1,clearInterval(){},
    fetch:(url,options)=>new Promise(resolve=>requests.push({url,options,resolve})),
  };
  vm.createContext(context);
  let source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web/app.js'),'utf8');
  source=source.replace(/\}\)\(\);\s*$/, `globalThis.fixture={
    setup(value){session=value;clientId='fixture';setConnection('connected');},
    pair,refresh,session:()=>session,state:()=>connection
  };})();`);
  vm.runInContext(source,context);
  const respond=(request,status,data)=>request.resolve({ok:status>=200&&status<300,status,json:async()=>data});
  return {get,body,requests,respond,fixture:context.fixture};
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const expired={code:'session_expired',error:'Connection expired'};
const state={files:[],texts:[],uploads:[],downloads:[]};

(async()=>{
  {
    const h=harness();h.fixture.setup('old');
    const poll=h.fixture.refresh(false);const oldPoll=h.requests.shift();
    const disconnect=h.get('disconnect-button').handlers.click();const deletion=h.requests.shift();
    h.respond(deletion,200,{});await disconnect;
    h.respond(oldPoll,401,expired);await poll;
    assert.equal(h.fixture.session(),null);
    assert.equal(h.fixture.state(),'unpaired');
    assert.equal(h.body.classes.has('connection-fault'),false);
  }
  {
    const h=harness();h.fixture.setup('old');
    const poll=h.fixture.refresh(false);const oldPoll=h.requests.shift();
    const pairing=h.fixture.pair('new-code');const request=h.requests.shift();
    h.respond(request,200,{session:'new',clientId:'new-client'});await pairing;
    h.respond(oldPoll,401,expired);await poll;
    assert.equal(h.fixture.session(),'new','Late old 401 must preserve the new credential');
    assert.equal(h.body.classes.has('connection-fault'),false);
    const refresh=h.fixture.refresh(false);h.respond(h.requests.shift(),200,state);await refresh;
    assert.equal(h.fixture.state(),'connected');
  }
  {
    const h=harness();h.fixture.setup('current');
    const poll=h.fixture.refresh(false);h.respond(h.requests.shift(),401,expired);await poll;
    assert.equal(h.fixture.session(),null);
    assert.equal(h.fixture.state(),'expired');
    assert.equal(h.body.classes.has('connection-fault'),true);
  }
  {
    const h=harness();h.fixture.setup('current');
    const disconnect=h.get('disconnect-button').handlers.click();h.respond(h.requests.shift(),401,expired);await disconnect;await tick();
    assert.equal(h.fixture.state(),'unpaired');
    assert.equal(h.body.classes.has('connection-fault'),false);
  }
  console.log('4 browser connection race checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
