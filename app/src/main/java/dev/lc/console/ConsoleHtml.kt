package dev.lc.console

/**
 * The console web UI, embedded as strings so the served pages are fully
 * self-contained: no third-party assets, fonts, analytics or CDN calls, and no
 * files to leak from the APK assets. The JS deliberately avoids template
 * literals so the Kotlin raw strings need no escaping.
 */
object ConsoleHtml {

    private const val CSS = """
body{margin:0;font-family:system-ui,sans-serif;background:#0b0f14;color:#dbe4ee}
.wrap{max-width:720px;margin:0 auto;padding:12px}
h1{font-size:1.25rem;margin:.2rem 0 .6rem}
.chip{display:inline-block;padding:2px 8px;border-radius:10px;font-size:.75rem;background:#1d2a3a;color:#9fc5ea;margin-right:6px}
.chip.warn{background:#4a1d1d;color:#f0b8b8}
input[type=text],input[type=password]{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #2c3a4d;background:#121a24;color:#dbe4ee;font-size:1rem}
button{padding:9px 14px;border-radius:8px;border:0;background:#2563eb;color:#fff;font-size:.95rem;margin:6px 6px 6px 0}
button.sec{background:#334155}
button.danger{background:#7f1d1d}
button:disabled{opacity:.5}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
pre{background:#05080b;border:1px solid #1e293b;border-radius:8px;padding:10px;min-height:220px;max-height:50vh;overflow:auto;white-space:pre-wrap;word-break:break-word;font-size:.85rem;margin:8px 0}
.err{color:#f0b8b8}
.sec-head{font-size:.8rem;text-transform:uppercase;letter-spacing:.08em;color:#7d93ab;margin-top:14px}
.meta{font-size:.8rem;color:#7d93ab}
select{max-width:100%;background:#121a24;color:#dbe4ee;border:1px solid #2c3a4d;border-radius:8px;padding:8px;font-size:.85rem}
"""

    val LOGIN_PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LC console</title><style>$CSS</style></head>
<body><div class="wrap"><div style="margin-top:8vh">
<h1>LC console</h1>
<p>This console is locked. Enter the pairing token shown in the LC app on this device.</p>
<input id="t" type="password" inputmode="text" autocomplete="off" placeholder="Pairing token">
<button id="go">Unlock</button>
<p id="e" class="err"></p>
</div></div>
<script>
function login(){var v=document.getElementById('t').value;
fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:v})})
.then(function(r){if(r.ok){location.reload();}else{document.getElementById('e').textContent='Wrong token (repeated failures lock this client out for a while).';}});}
document.getElementById('go').addEventListener('click',login);
document.getElementById('t').addEventListener('keydown',function(e){if(e.key==='Enter')login();});
</script></body></html>"""

    val CONSOLE_PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LC console</title><style>$CSS</style></head>
<body><div class="wrap">
<h1>LC console <span id="chips"></span></h1>
<div class="row">
<input id="cmd" type="text" placeholder="Command (allowlist: ls, df, du, free, uptime, id, uname, getprop, ps, top -n 1, logcat -d, ip addr, date, ping -c 1 ...)" style="flex:1 1 100%">
<button id="run">Run</button>
<button id="cancel" class="danger" disabled>Cancel</button>
<span id="meta" class="meta"></span>
</div>
<pre id="out"></pre>
<div class="sec-head">History</div>
<div class="row">
<select id="hist" size="1"><option value="">— recent commands —</option></select>
<button id="clearhist" class="sec">Clear history</button>
</div>
<p class="meta">Commands run as the LC app UID (never root). Every command and its result is written to the local audit log, viewable in the app.</p>
</div>
<script>
var out=document.getElementById('out');
var meta=document.getElementById('meta');
var runBtn=document.getElementById('run');
var cancelBtn=document.getElementById('cancel');
var es=null; var curId=null; var exitInfo=null;
function csrf(){var m=document.cookie.match(/(?:^|; )lc_csrf=([^;]+)/);return m?m[1]:'';}
function post(url,body){
 return fetch(url,{method:'POST',headers:{'Content-Type':'application/json','X-LC-CSRF':csrf()},body:body?JSON.stringify(body):'{}'});}
function on401(r){if(r.status===401){location.reload();return true;}return false;}
function line(cls,text){var s=document.createElement('span');if(cls)s.className=cls;s.appendChild(document.createTextNode(text));out.appendChild(s);}
fetch('/api/me').then(function(r){if(on401(r))return null;return r.json();}).then(function(me){
 if(!me)return;
 var chips=document.getElementById('chips');
 chips.innerHTML='';
 if(me.mode==='advanced'){var c=document.createElement('span');c.className='chip warn';c.textContent='ADVANCED MODE';chips.appendChild(c);}
 var b=document.createElement('span');b.className='chip';b.textContent=me.bind;chips.appendChild(b);
});
function loadHistory(){
 fetch('/api/history').then(function(r){if(on401(r))return;return r.json();}).then(function(h){
  if(!h)return;var sel=document.getElementById('hist');sel.textContent='';
  var o=document.createElement('option');o.value='';o.textContent='— recent commands —';sel.appendChild(o);
  h.items.forEach(function(c){var o=document.createElement('option');o.value=c;o.textContent=c;sel.appendChild(o);});
 });}
document.getElementById('hist').addEventListener('change',function(){if(this.value)document.getElementById('cmd').value=this.value;});
document.getElementById('clearhist').addEventListener('click',function(){post('/api/history/clear').then(loadHistory);});
runBtn.addEventListener('click',run);
document.getElementById('cmd').addEventListener('keydown',function(e){if(e.key==='Enter')run();});
cancelBtn.addEventListener('click',function(){if(curId)post('/api/cancel',{id:curId});});
function finish(d){
 var parts=[];
 if(d.status==='completed')parts.push('exit '+(d.exit===null?'?':d.exit));
 else parts.push(d.status+(d.exit===null?'':' (exit '+d.exit+')'));
 parts.push((d.duration_ms/1000).toFixed(2)+'s');
 if(d.truncated)parts.push('output capped at 1 MiB');
 if(d.spawn_error)parts.push('spawn error: '+d.spawn_error);
 meta.textContent=parts.join(' · ');
 runBtn.disabled=false;cancelBtn.disabled=true;
 try{es.close();}catch(e){}
 es=null;curId=null;loadHistory();}
function run(){
 var cmd=document.getElementById('cmd').value;
 if(!cmd)return;
 post('/api/run',{command:cmd}).then(function(r){
  if(on401(r))return;
  return r.json();
 }).then(function(j){
  if(!j)return;
  if(j.error){meta.textContent='Blocked: '+j.error;line('err','');return;}
  out.textContent='';meta.textContent='running…';exitInfo=null;
  runBtn.disabled=true;cancelBtn.disabled=false;curId=j.id;
  es=new EventSource('/api/stream?id='+j.id);
  es.addEventListener('out',function(ev){line('',JSON.parse(ev.data).text);});
  es.addEventListener('err',function(ev){line('err',JSON.parse(ev.data).text);});
  es.addEventListener('done',function(ev){finish(JSON.parse(ev.data));});
  es.onerror=function(){if(!exitInfo){meta.textContent='stream lost';runBtn.disabled=false;cancelBtn.disabled=true;}};
 });
}
loadHistory();
</script></body></html>"""
}
