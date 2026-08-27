from pathlib import Path
import json
root=Path('.')
js=(root/'app.js').read_text(encoding='utf-8')

# Add serialized cloud push state and background pull timer.
needle="  let cloudPushTimer = null;\n"
repl="  let cloudPushTimer = null;\n  let cloudPushInFlight = false;\n  let cloudPushQueued = false;\n  let cloudPollTimer = null;\n"
if needle not in js: raise SystemExit('cloudPushTimer declaration not found')
js=js.replace(needle,repl,1)

# Saved business changes should go to cloud immediately. Typing-only fields can still debounce.
old="""  function saveState({ cloud = true } = {}) {\n    state.updatedAt = new Date().toISOString();\n    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));\n    renderAll();\n    if (cloud) scheduleCloudPush();\n  }\n"""
new="""  function saveState({ cloud = true } = {}) {\n    state.updatedAt = new Date().toISOString();\n    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));\n    renderAll();\n    if (cloud) scheduleCloudPush(true);\n  }\n"""
if old not in js: raise SystemExit('saveState block not found')
js=js.replace(old,new,1)

# Make sync compare timestamps without re-uploading unchanged state, and support silent background sync.
old="""  async function syncCloud() {\n    if(!cloudConfigured()){toast('Cloud sync is not configured yet.');return;}\n    if(!loadAuth()?.access_token){toast('Sign in to cloud sync first.');return;}\n    try{\n      const remote=await pullCloud();\n      if(!remote){await pushCloud();return;}\n      const localTime=Date.parse(state.updatedAt||0),remoteTime=Date.parse(remote.data?.updatedAt||remote.updated_at||0);\n      if(remoteTime>localTime){state={...defaultState(),...remote.data};localStorage.setItem(STORAGE_KEY,JSON.stringify(state));renderAll();}\n      else await pushCloud();\n      renderCloudStatus();\n    }catch(e){toast(`Sync problem: ${e.message}`)}\n  }\n\n  function scheduleCloudPush(){clearTimeout(cloudPushTimer); if(cloudConfigured()&&loadAuth()?.access_token) cloudPushTimer=setTimeout(()=>pushCloud().catch(e=>console.warn(e)),1600)}\n"""
new="""  async function syncCloud({silent=false}={}) {\n    if(!cloudConfigured()){if(!silent)toast('Cloud sync is not configured yet.');return;}\n    if(!loadAuth()?.access_token){if(!silent)toast('Sign in to cloud sync first.');return;}\n    try{\n      const remote=await pullCloud();\n      if(!remote){await pushCloud();return;}\n      const localTime=Date.parse(state.updatedAt||0),remoteTime=Date.parse(remote.data?.updatedAt||remote.updated_at||0);\n      if(remoteTime>localTime){state={...defaultState(),...remote.data};localStorage.setItem(STORAGE_KEY,JSON.stringify(state));renderAll();}\n      else if(localTime>remoteTime) await pushCloud();\n      renderCloudStatus();\n    }catch(e){if(silent)console.warn('Auto-sync problem:',e);else toast(`Sync problem: ${e.message}`)}\n  }\n\n  async function runCloudPush(){\n    if(!cloudConfigured()||!loadAuth()?.access_token)return;\n    if(cloudPushInFlight){cloudPushQueued=true;return;}\n    cloudPushInFlight=true;\n    try{await pushCloud();}\n    catch(e){console.warn('Auto-sync push problem:',e);}\n    finally{cloudPushInFlight=false;if(cloudPushQueued){cloudPushQueued=false;scheduleCloudPush(true);}}\n  }\n  function scheduleCloudPush(immediate=false){\n    clearTimeout(cloudPushTimer);\n    if(!cloudConfigured()||!loadAuth()?.access_token)return;\n    cloudPushTimer=setTimeout(runCloudPush,immediate?40:650);\n  }\n  function startCloudAutoSync(){\n    if(cloudPollTimer)return;\n    cloudPollTimer=setInterval(()=>{if(!document.hidden&&navigator.onLine!==false&&cloudConfigured()&&loadAuth()?.access_token)syncCloud({silent:true});},5000);\n  }\n"""
if old not in js: raise SystemExit('syncCloud/scheduleCloudPush block not found')
js=js.replace(old,new,1)

# Update cloud status copy.
old="Signed in. Changes are saved locally first and then synced to Supabase while the app is open."
new="Signed in · Auto-sync on. Saved customer, schedule, route and job changes sync to Supabase automatically."
if old not in js: raise SystemExit('cloud status copy not found')
js=js.replace(old,new,1)

# Focus/visibility/online pulls, plus a small recurring pull while app is open.
old="""    window.addEventListener('focus',()=>{if(cloudConfigured()&&loadAuth()?.access_token)syncCloud().catch(()=>{});});\n  }\n\n  async function init() {\n    bindEvents();\n"""
new="""    window.addEventListener('focus',()=>{if(cloudConfigured()&&loadAuth()?.access_token)syncCloud({silent:true}).catch(()=>{});});\n    document.addEventListener('visibilitychange',()=>{if(!document.hidden&&cloudConfigured()&&loadAuth()?.access_token)syncCloud({silent:true}).catch(()=>{});});\n    window.addEventListener('online',()=>{if(cloudConfigured()&&loadAuth()?.access_token)syncCloud({silent:true}).catch(()=>{});});\n  }\n\n  async function init() {\n    bindEvents();\n    startCloudAutoSync();\n"""
if old not in js: raise SystemExit('focus/init block not found')
js=js.replace(old,new,1)

# Quick notes remain debounced while typing; saved business actions use immediate saveState pushes.
# Existing quickNotes calls scheduleCloudPush() with no arg, which now uses the 650ms typing debounce.

(root/'app.js').write_text(js,encoding='utf-8',newline='\n')

pkg=json.loads((root/'package.json').read_text(encoding='utf-8'))
pkg['version']='1.2.2'
(root/'package.json').write_text(json.dumps(pkg,indent=2)+'\n',encoding='utf-8',newline='\n')
print('v1.2.2 auto-sync patch applied',len(js))
