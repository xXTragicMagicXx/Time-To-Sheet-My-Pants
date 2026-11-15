// Timesheet service worker: keeps the app working offline.
// Network first (so updates show up), falling back to the saved copy when offline or slow.
const C='timesheet-cache';
const SHELL=['./','index.html','manifest.json','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
 const r=e.request;if(r.method!=='GET')return;
 const u=new URL(r.url);
 if(u.origin===location.origin){
  e.respondWith(new Promise(ok=>{
   const net=fetch(r).then(res=>{if(res.ok){const cp=res.clone();caches.open(C).then(c=>c.put(r,cp))}return res});
   const t=setTimeout(()=>caches.match(r,{ignoreSearch:true}).then(m=>{if(m)ok(m)}),2500);
   net.then(res=>{clearTimeout(t);ok(res)}).catch(()=>caches.match(r,{ignoreSearch:true}).then(m=>{clearTimeout(t);ok(m||Response.error())}));
  }));
 }else if(u.hostname==='cdnjs.cloudflare.com'){
  // PDF library: save it the first time it loads so invoices work offline afterward
  e.respondWith(caches.match(r).then(m=>m||fetch(r).then(res=>{const cp=res.clone();caches.open(C).then(c=>c.put(r,cp));return res})));
 }
});
