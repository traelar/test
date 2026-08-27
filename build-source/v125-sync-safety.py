from pathlib import Path
import json

root=Path('.')
app=(root/'app.js').read_text(encoding='utf-8')
pkg=json.loads((root/'package.json').read_text(encoding='utf-8'))
sw=(root/'service-worker.js').read_text(encoding='utf-8')

# v1.2.5: non-destructive cloud merge. A fresh/partial device must never replace a fuller cloud state.
state_fn="function stateHasBusinessData(s){const base=defaultState();return !!((s?.customers?.length||0)||(s?.appointments?.length||0)||(s?.textLog?.length||0)||(s?.quickNotes)||(s?.fuelSnapshot)||JSON.stringify(s?.settings||{})!==JSON.stringify(base.settings));}"
if state_fn not in app:
    raise SystemExit('stateHasBusinessData target not found')
helpers=r'''
  function cloudStamp(v){const n=Date.parse(v||0);return Number.isFinite(n)?n:0;}
  function ensureCloudTombstones(s){
    if(!s.cloudTombstones||typeof s.cloudTombstones!=='object')s.cloudTombstones={};
    if(!s.cloudTombstones.customers||typeof s.cloudTombstones.customers!=='object')s.cloudTombstones.customers={};
    if(!s.cloudTombstones.appointments||typeof s.cloudTombstones.appointments!=='object')s.cloudTombstones.appointments={};
    return s.cloudTombstones;
  }
  function markCloudDeletion(kind,id){if(!id)return;const tomb=ensureCloudTombstones(state);tomb[kind][id]=new Date().toISOString();}
  function mergeTombstoneMap(a={},b={}){const out={...a};for(const [id,when] of Object.entries(b||{})){if(cloudStamp(when)>=cloudStamp(out[id]))out[id]=when;}return out;}
  function mergeEntityList(localList=[],remoteList=[],localTime=0,remoteTime=0,tombstones={}){
    const localBy=new Map((Array.isArray(localList)?localList:[]).filter(x=>x&&x.id).map(x=>[String(x.id),x]));
    const remoteBy=new Map((Array.isArray(remoteList)?remoteList:[]).filter(x=>x&&x.id).map(x=>[String(x.id),x]));
    const ids=new Set([...localBy.keys(),...remoteBy.keys()]);const out=[];
    for(const id of ids){
      if(tombstones&&tombstones[id])continue;
      const l=localBy.get(id),r=remoteBy.get(id);
      if(l&&r){
        const lt=cloudStamp(l.updatedAt||l.modifiedAt||l.savedAt)||localTime;
        const rt=cloudStamp(r.updatedAt||r.modifiedAt||r.savedAt)||remoteTime;
        out.push(lt>=rt?l:r);
      }else out.push(l||r);
    }
    return out;
  }
  function mergeTextLog(localList=[],remoteList=[],deletedCustomers={}){
    const out=[],seen=new Set();
    for(const row of [...(Array.isArray(localList)?localList:[]),...(Array.isArray(remoteList)?remoteList:[])]){
      if(!row)continue;if(row.customerId&&deletedCustomers?.[row.customerId])continue;
      const key=row.id?`id:${row.id}`:JSON.stringify(row);if(seen.has(key))continue;seen.add(key);out.push(row);
    }
    return out;
  }
  function recoverySnapshot(s){
    const src=normalizeState(s);return {at:new Date().toISOString(),customers:src.customers||[],appointments:src.appointments||[],textLog:src.textLog||[],settings:src.settings||{},quickNotes:src.quickNotes||'',fuelSnapshot:src.fuelSnapshot||null};
  }
  function mergeRecovery(a=[],b=[]){const rows=[...(Array.isArray(a)?a:[]),...(Array.isArray(b)?b:[])].filter(x=>x&&x.at);const seen=new Set();return rows.sort((x,y)=>cloudStamp(y.at)-cloudStamp(x.at)).filter(x=>{if(seen.has(x.at))return false;seen.add(x.at);return true;}).slice(0,5);}
  function recoverFromCloudHistory(input){
    const src=normalizeState(input);if(stateHasBusinessData(src))return src;
    const snap=(Array.isArray(src.cloudRecovery)?src.cloudRecovery:[]).find(x=>x&&((x.customers||[]).length||(x.appointments||[]).length||(x.textLog||[]).length));
    return snap?normalizeState({...src,...snap,cloudRecovery:src.cloudRecovery,cloudTombstones:src.cloudTombstones,updatedAt:snap.at||src.updatedAt}):src;
  }
  function mergeCloudStates(localInput,remoteInput,remoteRowTime=''){
    const local=normalizeState(localInput),remote=recoverFromCloudHistory(remoteInput);
    const lt=cloudStamp(local.updatedAt),rt=cloudStamp(remote.updatedAt||remoteRowTime);
    const newer=lt>=rt?local:remote,older=lt>=rt?remote:local;
    const merged=normalizeState({...older,...newer});
    const localT=ensureCloudTombstones(local),remoteT=ensureCloudTombstones(remote);
    merged.cloudTombstones={customers:mergeTombstoneMap(localT.customers,remoteT.customers),appointments:mergeTombstoneMap(localT.appointments,remoteT.appointments)};
    merged.customers=mergeEntityList(local.customers,remote.customers,lt,rt,merged.cloudTombstones.customers);
    merged.appointments=mergeEntityList(local.appointments,remote.appointments,lt,rt,merged.cloudTombstones.appointments).filter(a=>!merged.cloudTombstones.customers?.[a.customerId]);
    merged.textLog=mergeTextLog(local.textLog,remote.textLog,merged.cloudTombstones.customers);
    merged.cloudRecovery=mergeRecovery(local.cloudRecovery,remote.cloudRecovery);
    return merged;
  }
  function sameCloudCore(a,b){
    const clean=(s)=>{const n=normalizeState(s),copy={...n};delete copy.updatedAt;delete copy.cloudRecovery;return JSON.stringify(copy)};
    return clean(a)===clean(b);
  }
  async function uploadCloudState(next){
    const auth=loadAuth(),payload=parseJwt(auth.access_token),userId=payload.sub;if(!userId)throw new Error('Could not identify cloud account.');
    await apiFetch('app_state?on_conflict=user_id',{method:'POST',headers:{Prefer:'resolution=merge-duplicates,return=minimal'},body:JSON.stringify({user_id:userId,data:next,updated_at:next.updatedAt})});
  }
'''
app=app.replace(state_fn,state_fn+'\n'+helpers,1)

old_push="""  async function pushCloud({force=false}={}) {\n    if(!cloudConfigured()||!loadAuth()?.access_token)return;\n    if(!force&&!stateHasBusinessData(state)){\n      try{const remote=await pullCloud();if(remote&&stateHasBusinessData(remote.data)){state=normalizeState(remote.data);writeLocalState(state);renderAll();renderCloudStatus();return;}}catch{}\n    }\n    const auth=loadAuth(),payload=parseJwt(auth.access_token);const userId=payload.sub;if(!userId)throw new Error('Could not identify cloud account.');\n    if(!state.updatedAt)state.updatedAt=new Date().toISOString();\n    await apiFetch('app_state?on_conflict=user_id',{method:'POST',headers:{Prefer:'resolution=merge-duplicates,return=minimal'},body:JSON.stringify({user_id:userId,data:state,updated_at:state.updatedAt})});\n    renderCloudStatus();\n  }\n"""
new_push="""  async function pushCloud({force=false}={}) {\n    if(!cloudConfigured()||!loadAuth()?.access_token)return;\n    let remote=null;\n    try{remote=await pullCloud();}catch(err){console.warn('Cloud safety pull failed; upload postponed.',err);if(!force)return;throw err;}\n    if(remote){\n      const remoteState=recoverFromCloudHistory(remote.data);\n      const merged=mergeCloudStates(state,remoteState,remote.updated_at);\n      if(stateHasBusinessData(remoteState)&&!sameCloudCore(merged,remoteState)){merged.cloudRecovery=mergeRecovery([recoverySnapshot(remoteState)],merged.cloudRecovery);}\n      state=merged;\n    }else if(!stateHasBusinessData(state)&&!force){renderCloudStatus();return;}\n    if(!stateHasBusinessData(state)&&remote){state=recoverFromCloudHistory(remote.data);writeLocalState(state);renderAll();renderCloudStatus();return;}\n    state.updatedAt=new Date().toISOString();writeLocalState(state);\n    await uploadCloudState(state);renderAll();renderCloudStatus();\n  }\n"""
if old_push not in app:
    raise SystemExit('pushCloud v1.2.4 target not found')
app=app.replace(old_push,new_push,1)

old_sync="""      const remote=await pullCloud();\n      if(!remote){if(stateHasBusinessData(state))await pushCloud({force:true});return;}\n      const remoteState=normalizeState(remote.data),localHas=stateHasBusinessData(state),remoteHas=stateHasBusinessData(remoteState);\n      if(!localHas&&remoteHas){state=remoteState;writeLocalState(state);renderAll();renderCloudStatus();return;}\n      if(localHas&&!remoteHas){await pushCloud({force:true});renderCloudStatus();return;}\n      if(!localHas&&!remoteHas){renderCloudStatus();return;}\n      const localTime=Date.parse(state.updatedAt||0)||0,remoteTime=Date.parse(remoteState.updatedAt||remote.updated_at||0)||0;\n      if(remoteTime>localTime){writeLocalState(state);state=remoteState;writeLocalState(state);renderAll();}\n      else if(localTime>remoteTime)await pushCloud({force:true});\n      renderCloudStatus();\n"""
new_sync="""      const remote=await pullCloud();\n      if(!remote){if(stateHasBusinessData(state)){state.updatedAt=state.updatedAt||new Date().toISOString();await uploadCloudState(state);}renderCloudStatus();return;}\n      const remoteState=recoverFromCloudHistory(remote.data);\n      const merged=mergeCloudStates(state,remoteState,remote.updated_at);\n      const changedLocal=!sameCloudCore(merged,state),changedRemote=!sameCloudCore(merged,remoteState);\n      if(changedRemote&&stateHasBusinessData(remoteState))merged.cloudRecovery=mergeRecovery([recoverySnapshot(remoteState)],merged.cloudRecovery);\n      state=merged;writeLocalState(state);if(changedLocal)renderAll();\n      if(changedRemote){state.updatedAt=new Date().toISOString();writeLocalState(state);await uploadCloudState(state);}\n      renderCloudStatus();\n"""
if old_sync not in app:
    raise SystemExit('syncCloud v1.2.4 target not found')
app=app.replace(old_sync,new_sync,1)

old_customer="""  async function deleteCustomer() {\n    const id=$('#customerId').value; if(!id)return;\n    const c=customerById(id); const ok=await askConfirm(`Delete ${customerName(c)}? This will also delete their appointments and text history.`,{title:'Delete Customer',confirmText:'Delete Customer',danger:true}); if(!ok)return;\n    state.customers=state.customers.filter(c=>c.id!==id); state.appointments=state.appointments.filter(a=>a.customerId!==id); state.textLog=state.textLog.filter(t=>t.customerId!==id);\n    closeDialogWithoutSave($('#customerDialog')); saveState(); toast('Customer deleted.');\n  }\n"""
new_customer="""  async function deleteCustomer() {\n    const id=$('#customerId').value; if(!id)return;\n    const c=customerById(id); const ok=await askConfirm(`Delete ${customerName(c)}? This will also delete their appointments and text history.`,{title:'Delete Customer',confirmText:'Delete Customer',danger:true}); if(!ok)return;\n    markCloudDeletion('customers',id);state.appointments.filter(a=>a.customerId===id).forEach(a=>markCloudDeletion('appointments',a.id));\n    state.customers=state.customers.filter(c=>c.id!==id); state.appointments=state.appointments.filter(a=>a.customerId!==id); state.textLog=state.textLog.filter(t=>t.customerId!==id);\n    closeDialogWithoutSave($('#customerDialog')); saveState(); toast('Customer deleted.');\n  }\n"""
if old_customer not in app:
    raise SystemExit('deleteCustomer v1.2.4 target not found')
app=app.replace(old_customer,new_customer,1)

old_appt="""  async function deleteAppointment() {\n    const id=$('#appointmentId').value; if(!id)return; const a=appointmentById(id);const c=customerById(a?.customerId);\n    const ok=await askConfirm(`Delete this appointment${c?` for ${customerName(c)}`:''}? This deletes only this visit.`,{title:'Delete Appointment',confirmText:'Delete Appointment',danger:true});if(!ok)return;\n    state.appointments=state.appointments.filter(a=>a.id!==id); closeDialogWithoutSave($('#appointmentDialog')); saveState(); toast('Appointment deleted.');\n  }\n"""
new_appt="""  async function deleteAppointment() {\n    const id=$('#appointmentId').value; if(!id)return; const a=appointmentById(id);const c=customerById(a?.customerId);\n    const ok=await askConfirm(`Delete this appointment${c?` for ${customerName(c)}`:''}? This deletes only this visit.`,{title:'Delete Appointment',confirmText:'Delete Appointment',danger:true});if(!ok)return;\n    markCloudDeletion('appointments',id);state.appointments=state.appointments.filter(a=>a.id!==id); closeDialogWithoutSave($('#appointmentDialog')); saveState(); toast('Appointment deleted.');\n  }\n"""
if old_appt not in app:
    raise SystemExit('deleteAppointment v1.2.4 target not found')
app=app.replace(old_appt,new_appt,1)

# Version bump and service worker cache bump.
pkg['version']='1.2.5'
(root/'package.json').write_text(json.dumps(pkg,indent=2)+'\n',encoding='utf-8',newline='\n')
sw=sw.replace('clear-choice-v124','clear-choice-v125').replace('v1.2.4','v1.2.5')
(root/'service-worker.js').write_text(sw,encoding='utf-8',newline='\n')
(root/'app.js').write_text(app,encoding='utf-8',newline='\n')
print('patched v1.2.5 safe cloud merge',len(app))
