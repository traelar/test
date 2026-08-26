from pathlib import Path
root=Path('.')
html=(root/'index.html').read_text(); js=(root/'app.js').read_text(); css=(root/'styles.css').read_text()
input_html='''\n  <dialog id="inputDialog" class="dialog small-dialog input-dialog">\n    <div class="dialog-head"><div><p class="eyebrow">Enter details</p><h2 id="inputDialogTitle">Add Information</h2></div><button type="button" class="icon-btn" id="inputDialogX" aria-label="Close">×</button></div>\n    <div class="dialog-body"><p id="inputDialogMessage" class="confirm-message"></p><textarea id="inputDialogValue" rows="4" autocomplete="off"></textarea></div>\n    <div class="dialog-actions"><span class="spacer"></span><button type="button" class="secondary" id="inputDialogCancel">Cancel</button><button type="button" class="primary" id="inputDialogOk">Save</button></div>\n  </dialog>\n'''
html=html.replace('\n  <dialog id="confirmDialog"', input_html+'\n  <dialog id="confirmDialog"',1)
# state
js=js.replace('  let confirmResolver = null;\n', '  let confirmResolver = null;\n  let inputResolver = null;\n',1)
needle='''  function closeDialogWithoutSave(dialog){if(dialog?.open)dialog.close();}\n\n'''
insert=needle+'''  function askInput(message,{title='Add Information',defaultValue='',saveText='Save'}={}) {\n    const dlg=$('#inputDialog');if(!dlg)return Promise.resolve(null);\n    if(inputResolver){inputResolver(null);inputResolver=null;}\n    $('#inputDialogTitle').textContent=title;$('#inputDialogMessage').textContent=message;$('#inputDialogValue').value=String(defaultValue??'');$('#inputDialogOk').textContent=saveText;\n    return new Promise(resolve=>{inputResolver=resolve;dlg.showModal();setTimeout(()=>{$('#inputDialogValue').focus();$('#inputDialogValue').select();},0);});\n  }\n  function resolveInput(save){const dlg=$('#inputDialog');const value=save?$('#inputDialogValue').value:null;if(dlg?.open)dlg.close();const resolve=inputResolver;inputResolver=null;if(resolve)resolve(value);}\n\n'''
if needle not in js: raise SystemExit('close needle missing')
js=js.replace(needle,insert,1)
# finish use ask input if not started
old="""    const now=new Date();if(!a.startedAt){const guess=numberOrZero(c?.expectedMinutes)||120;a.startedAt=new Date(now.getTime()-guess*60000).toISOString();}\n"""
new="""    const now=new Date();if(!a.startedAt){const raw=await askInput('You did not start the timer. About how many minutes did this cleaning take?',{title:'Cleaning Time',defaultValue:String(numberOrZero(c?.expectedMinutes)||120),saveText:'Use This Time'});if(raw===null)return;const guess=Math.max(1,numberOrZero(raw)||numberOrZero(c?.expectedMinutes)||120);a.startedAt=new Date(now.getTime()-guess*60000).toISOString();}\n"""
if old not in js: raise SystemExit('finish guess missing')
js=js.replace(old,new,1)
old="""  async function saveAttachmentFile(file,kind){const a=appointmentFromDialog();if(!a||!file)return;let label=kind==='photo'?(prompt('Label this photo: Before, After, or other?','Before')||'Photo'):'Voice note';const id=uid();const rec={id,appointmentId:a.id,kind,label,name:file.name||label,mime:file.type||'',blob:file,createdAt:new Date().toISOString()};try{await putAttachment(rec);a.attachmentsMeta=[...(a.attachmentsMeta||[]),{id,kind,label,name:rec.name,mime:rec.mime,createdAt:rec.createdAt,deviceLocal:true}];saveState();renderAttachments(a);toast(`${kind==='photo'?'Photo':'Voice note'} saved on this device.`);}catch(e){toast('Could not save that attachment on this device.');}}\n"""
new="""  async function saveAttachmentFile(file,kind){const a=appointmentFromDialog();if(!a||!file)return;let label='Voice note';if(kind==='photo'){const entered=await askInput('Label this photo so it is easy to find later (Before, After, etc.).',{title:'Photo Label',defaultValue:'Before',saveText:'Save Photo'});if(entered===null)return;label=entered.trim()||'Photo';}const id=uid();const rec={id,appointmentId:a.id,kind,label,name:file.name||label,mime:file.type||'',blob:file,createdAt:new Date().toISOString()};try{await putAttachment(rec);a.attachmentsMeta=[...(a.attachmentsMeta||[]),{id,kind,label,name:rec.name,mime:rec.mime,createdAt:rec.createdAt,deviceLocal:true}];saveState();renderAttachments(a);toast(`${kind==='photo'?'Photo':'Voice note'} saved on this device.`);}catch(e){toast('Could not save that attachment on this device.');}}\n"""
if old not in js: raise SystemExit('attachment block missing')
js=js.replace(old,new,1)
old="""  function addWrittenNote(){const a=appointmentFromDialog();if(!a)return;const text=prompt('Add a note for this cleaning:');if(!text?.trim())return;a.fieldNotes=[...(a.fieldNotes||[]),{id:uid(),text:text.trim(),createdAt:new Date().toISOString()}];persistWorkAppointment(a,'Note added.');}\n"""
new="""  async function addWrittenNote(){const a=appointmentFromDialog();if(!a)return;const text=await askInput('Add a note for this cleaning:',{title:'Cleaning Note',defaultValue:'',saveText:'Add Note'});if(!text?.trim())return;a.fieldNotes=[...(a.fieldNotes||[]),{id:uid(),text:text.trim(),createdAt:new Date().toISOString()}];persistWorkAppointment(a,'Note added.');}\n"""
if old not in js: raise SystemExit('written note block missing')
js=js.replace(old,new,1)
# bind input dialogs after confirm
needle="    $('#confirmDialog').addEventListener('cancel',e=>{e.preventDefault();resolveConfirm(false);});\n"
insert=needle+"    $('#inputDialogOk').addEventListener('click',()=>resolveInput(true)); $('#inputDialogCancel').addEventListener('click',()=>resolveInput(false)); $('#inputDialogX').addEventListener('click',()=>resolveInput(false)); $('#inputDialog').addEventListener('cancel',e=>{e.preventDefault();resolveInput(false);});\n"
js=js.replace(needle,insert,1)
css += '\n.input-dialog textarea{width:100%;min-height:96px;resize:vertical}\n'
(root/'index.html').write_text(html);(root/'app.js').write_text(js);(root/'styles.css').write_text(css)
print('done')
