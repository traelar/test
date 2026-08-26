from pathlib import Path
import re, json
root=Path('.')
html=(root/'index.html').read_text()
js=(root/'app.js').read_text()
css=(root/'styles.css').read_text()

# Explicit non-submit dialog controls + generic close hooks.
html=html.replace('<form method="dialog" id="customerForm">','<form id="customerForm">')
html=html.replace('<form method="dialog" id="appointmentForm">','<form id="appointmentForm">')
html=html.replace('<form method="dialog" id="templateForm">','<form id="templateForm">')
html=html.replace('<button class="icon-btn" value="cancel" aria-label="Close">×</button>', '<button type="button" class="icon-btn" data-dialog-close aria-label="Close">×</button>')
html=html.replace('<button value="cancel" class="secondary">Cancel</button>', '<button type="button" class="secondary" data-dialog-close>Cancel</button>')

confirm_html='''\n  <dialog id="confirmDialog" class="dialog small-dialog confirm-dialog">\n    <div class="dialog-head"><div><p class="eyebrow">Please confirm</p><h2 id="confirmDialogTitle">Confirm Action</h2></div><button type="button" class="icon-btn" id="confirmDialogX" aria-label="Close">×</button></div>\n    <div class="dialog-body"><p id="confirmDialogMessage" class="confirm-message"></p></div>\n    <div class="dialog-actions"><span class="spacer"></span><button type="button" class="secondary" id="confirmDialogCancel">Cancel</button><button type="button" class="primary" id="confirmDialogOk">Confirm</button></div>\n  </dialog>\n'''
html=html.replace('\n  <div class="toast" id="toast" role="status" aria-live="polite"></div>', confirm_html+'\n  <div class="toast" id="toast" role="status" aria-live="polite"></div>')

# Phone formatting helper after normalizePhone.
needle="  const normalizePhone = (s) => String(s || '').replace(/[^\\d+]/g, '');\n"
insert=needle+'''  const formatUsPhone = (value) => {\n    const raw=String(value||''); let digits=raw.replace(/\\D/g,'').slice(0,11);\n    const hasCountry=digits.length===11&&digits.startsWith('1'); if(hasCountry)digits=digits.slice(1); else digits=digits.slice(0,10);\n    let out=''; if(digits.length<=3)out=digits; else if(digits.length<=6)out=`${digits.slice(0,3)}-${digits.slice(3)}`; else out=`${digits.slice(0,3)}-${digits.slice(3,6)}-${digits.slice(6,10)}`;\n    return hasCountry&&out?`1-${out}`:out;\n  };\n'''
if needle not in js: raise SystemExit('normalize needle missing')
js=js.replace(needle,insert,1)

# Confirm controller near top after cloudPushTimer.
needle="  let cloudPushTimer = null;\n"
insert=needle+'''  let confirmResolver = null;\n\n  function askConfirm(message,{title='Confirm Action',confirmText='Confirm',cancelText='Cancel',danger=false}={}) {\n    const dlg=$('#confirmDialog');\n    if(!dlg) return Promise.resolve(false);\n    if(confirmResolver){confirmResolver(false);confirmResolver=null;}\n    $('#confirmDialogTitle').textContent=title; $('#confirmDialogMessage').textContent=message;\n    $('#confirmDialogOk').textContent=confirmText; $('#confirmDialogCancel').textContent=cancelText;\n    $('#confirmDialogOk').className=danger?'danger':'primary';\n    return new Promise(resolve=>{confirmResolver=resolve;dlg.showModal();});\n  }\n  function resolveConfirm(value){\n    const dlg=$('#confirmDialog'); if(dlg?.open)dlg.close();\n    const resolve=confirmResolver;confirmResolver=null;if(resolve)resolve(!!value);\n  }\n  function closeDialogWithoutSave(dialog){if(dialog?.open)dialog.close();}\n\n'''
if needle not in js: raise SystemExit('cloud timer needle missing')
js=js.replace(needle,insert,1)

# Phone rendering/save.
js=js.replace("$('#customerFirstName').value=c?.firstName||''; $('#customerLastName').value=c?.lastName||''; $('#customerPhone').value=c?.phone||'';", "$('#customerFirstName').value=c?.firstName||''; $('#customerLastName').value=c?.lastName||''; $('#customerPhone').value=formatUsPhone(c?.phone||'');")
js=js.replace("phone:$('#customerPhone').value.trim(), email:$('#customerEmail').value.trim(),", "phone:formatUsPhone($('#customerPhone').value), email:$('#customerEmail').value.trim(),")
js=js.replace("$('#ownerPhone').value=state.settings.ownerPhone||'';", "$('#ownerPhone').value=formatUsPhone(state.settings.ownerPhone||'');")
js=js.replace("state.settings.ownerPhone=$('#ownerPhone').value.trim();", "state.settings.ownerPhone=formatUsPhone($('#ownerPhone').value);")

# Replace destructive/native confirms with app confirm dialog.
old='''  function deleteCustomer() {\n    const id=$('#customerId').value; if(!id)return;\n    const c=customerById(id); if(!confirm(`Delete ${customerName(c)}? This will also delete their appointments and text log.`))return;\n    state.customers=state.customers.filter(c=>c.id!==id); state.appointments=state.appointments.filter(a=>a.customerId!==id); state.textLog=state.textLog.filter(t=>t.customerId!==id);\n    $('#customerDialog').close(); saveState(); toast('Customer deleted.');\n  }\n'''
new='''  async function deleteCustomer() {\n    const id=$('#customerId').value; if(!id)return;\n    const c=customerById(id); const ok=await askConfirm(`Delete ${customerName(c)}? This will also delete their appointments and text history.`,{title:'Delete Customer',confirmText:'Delete Customer',danger:true}); if(!ok)return;\n    state.customers=state.customers.filter(c=>c.id!==id); state.appointments=state.appointments.filter(a=>a.customerId!==id); state.textLog=state.textLog.filter(t=>t.customerId!==id);\n    closeDialogWithoutSave($('#customerDialog')); saveState(); toast('Customer deleted.');\n  }\n'''
if old not in js: raise SystemExit('deleteCustomer block missing')
js=js.replace(old,new)

# Appointment save -> async future question.
js=js.replace('  function saveAppointmentFromForm(e) {\n', '  async function saveAppointmentFromForm(e) {\n',1)
js=js.replace("    if(dateChanged && confirm('Move all future scheduled cleanings for this customer by the same date/time change? Press Cancel to move only this cleaning.')) shiftFutureAppointments(existing,a);", "    if(dateChanged){const moveFuture=await askConfirm('Move all future scheduled cleanings for this customer by the same date/time change?',{title:'Update Recurring Schedule',confirmText:'Move Future Too',cancelText:'Only This One'});if(moveFuture)shiftFutureAppointments(existing,a);}")

# Finish cleaning and skip.
old='''  function finishCleaning(){\n    const a=appointmentFromDialog();if(!a)return;const c=customerById(a.customerId);if(a.status==='completed'){toast('This cleaning is already completed.');return;}\n    const now=new Date();if(!a.startedAt){const guess=numberOrZero(c?.expectedMinutes)||Number(prompt('How many minutes did this cleaning take?','120'))||120;a.startedAt=new Date(now.getTime()-guess*60000).toISOString();}\n    a.completedAt=now.toISOString();a.durationMinutes=Math.max(1,Math.round((Date.parse(a.completedAt)-Date.parse(a.startedAt))/60000));a.status='completed';a.miles=numberOrZero($('#appointmentMiles').value);a.expenses=numberOrZero($('#appointmentExpenses').value);a.vehicleId=$('#appointmentVehicle').value||state.settings.activeVehicleId||'';a.fuelPrice=numberOrZero($('#appointmentFuelPrice').value)||numberOrZero(state.fuelSnapshot?.price);a.payment=confirm(`Mark ${money(a.price)} as paid now?`)?'paid':'unpaid';ensureNextAppointment(a,c);persistWorkAppointment(a,`Cleaning finished · ${formatDuration(a.durationMinutes)} · ${money(grossHourly(a))}/hr gross.`);\n  }\n  function skipCleaning(){const a=appointmentFromDialog();if(!a)return;if(!confirm('Skip this visit but keep the recurring schedule going?'))return;const c=customerById(a.customerId);a.status='skipped';a.reminder='skipped';a.payment='not_due';a.skippedAt=new Date().toISOString();ensureNextAppointment(a,c);persistWorkAppointment(a,'Visit skipped. The next recurring cleaning stays scheduled.');}\n'''
new='''  async function finishCleaning(){\n    const a=appointmentFromDialog();if(!a)return;const c=customerById(a.customerId);if(a.status==='completed'){toast('This cleaning is already completed.');return;}\n    const now=new Date();if(!a.startedAt){const guess=numberOrZero(c?.expectedMinutes)||120;a.startedAt=new Date(now.getTime()-guess*60000).toISOString();}\n    a.completedAt=now.toISOString();a.durationMinutes=Math.max(1,Math.round((Date.parse(a.completedAt)-Date.parse(a.startedAt))/60000));a.status='completed';a.miles=numberOrZero($('#appointmentMiles').value);a.expenses=numberOrZero($('#appointmentExpenses').value);a.vehicleId=$('#appointmentVehicle').value||state.settings.activeVehicleId||'';a.fuelPrice=numberOrZero($('#appointmentFuelPrice').value)||numberOrZero(state.fuelSnapshot?.price);\n    const paid=await askConfirm(`Mark ${money(a.price)} as paid now?`,{title:'Cleaning Finished',confirmText:'Yes, Paid',cancelText:'Not Paid Yet'});a.payment=paid?'paid':'unpaid';ensureNextAppointment(a,c);persistWorkAppointment(a,`Cleaning finished · ${formatDuration(a.durationMinutes)} · ${money(grossHourly(a))}/hr gross.`);\n  }\n  async function skipCleaning(){const a=appointmentFromDialog();if(!a)return;const ok=await askConfirm('Skip this visit but keep the recurring schedule going?',{title:'Skip Cleaning',confirmText:'Skip This Visit',danger:true});if(!ok)return;const c=customerById(a.customerId);a.status='skipped';a.reminder='skipped';a.payment='not_due';a.skippedAt=new Date().toISOString();ensureNextAppointment(a,c);persistWorkAppointment(a,'Visit skipped. The next recurring cleaning stays scheduled.');}\n'''
if old not in js: raise SystemExit('finish/skip block missing')
js=js.replace(old,new)

old='''  function deleteAppointment() {\n    const id=$('#appointmentId').value; if(!id)return; if(!confirm('Delete this appointment?'))return;\n    state.appointments=state.appointments.filter(a=>a.id!==id); $('#appointmentDialog').close(); saveState(); toast('Appointment deleted.');\n  }\n'''
new='''  async function deleteAppointment() {\n    const id=$('#appointmentId').value; if(!id)return; const a=appointmentById(id);const c=customerById(a?.customerId);\n    const ok=await askConfirm(`Delete this appointment${c?` for ${customerName(c)}`:''}? This deletes only this visit.`,{title:'Delete Appointment',confirmText:'Delete Appointment',danger:true});if(!ok)return;\n    state.appointments=state.appointments.filter(a=>a.id!==id); closeDialogWithoutSave($('#appointmentDialog')); saveState(); toast('Appointment deleted.');\n  }\n'''
if old not in js: raise SystemExit('deleteAppointment block missing')
js=js.replace(old,new)

js=js.replace("  function resetApp() { if(!confirm('Reset the app and delete all local customers, appointments and settings? Export a backup first if you may need them.'))return; state=defaultState(); localStorage.removeItem(STORAGE_KEY); saveState({cloud:false}); toast('App reset.'); }", "  async function resetApp() { const ok=await askConfirm('Reset the app and delete all local customers, appointments and settings? Export a backup first if you may need them.',{title:'Reset Clear Choice',confirmText:'Reset Everything',danger:true});if(!ok)return; state=defaultState(); localStorage.removeItem(STORAGE_KEY); saveState({cloud:false}); toast('App reset.'); }")

# Bind dialog-close/confirm + phone formatting early in bindEvents.
needle="  function bindEvents() {\n"
insert=needle+'''    document.addEventListener('click',e=>{const close=e.target.closest('[data-dialog-close]');if(close){e.preventDefault();const dlg=close.closest('dialog');closeDialogWithoutSave(dlg);}});\n    $('#confirmDialogOk').addEventListener('click',()=>resolveConfirm(true)); $('#confirmDialogCancel').addEventListener('click',()=>resolveConfirm(false)); $('#confirmDialogX').addEventListener('click',()=>resolveConfirm(false));\n    $('#confirmDialog').addEventListener('cancel',e=>{e.preventDefault();resolveConfirm(false);});\n    ['#customerPhone','#ownerPhone'].forEach(sel=>$(sel)?.addEventListener('input',e=>{const before=e.target.value;const formatted=formatUsPhone(before);if(before!==formatted)e.target.value=formatted;}));\n'''
if needle not in js: raise SystemExit('bindEvents needle missing')
js=js.replace(needle,insert,1)

# Make dialog native Cancel/Escape never submit; clear timer when appointment closes.
needle="    window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();deferredInstallPrompt=e;$('#installBtn').hidden=false;});\n"
insert="    $('#appointmentDialog').addEventListener('close',()=>clearInterval(workTimerInterval));\n"+needle
js=js.replace(needle,insert,1)

# Config built-in Supabase client values (publishable key only).
config='''// Clear Choice Cleaning cloud connection.\n// The publishable key is safe for client apps; never place a Supabase secret/service-role key here.\nwindow.CLEAR_CHOICE_CONFIG = {\n  supabaseUrl: "https://gyiykhjgiicszmoigjaw.supabase.co",\n  supabaseAnonKey: "sb_publishable_e2dFnZZO5zA6oopLJ8_3Uw_w1VQ6sv6"\n};\n'''
(root/'config.js').write_text(config)

# Version bump package + artifact.
pkg=json.loads((root/'package.json').read_text())
pkg['version']='1.2.1'
(root/'package.json').write_text(json.dumps(pkg,indent=2)+"\n")

# Useful CSS for confirm dialog.
css += '''\n.confirm-dialog{max-width:460px}.confirm-message{font-size:15px;line-height:1.55;color:var(--text);margin:0}.confirm-dialog .danger{background:#b42318;color:#fff;border-color:#b42318}\n'''

(root/'index.html').write_text(html)
(root/'app.js').write_text(js)
(root/'styles.css').write_text(css)
print('patched',len(js),len(html),len(css))
