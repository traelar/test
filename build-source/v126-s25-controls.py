from pathlib import Path
import json,re
root=Path('.')
app=(root/'app.js').read_text(encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8')
css=(root/'styles.css').read_text(encoding='utf-8')
pkg=json.loads((root/'package.json').read_text(encoding='utf-8'))
manifest=json.loads((root/'manifest.json').read_text(encoding='utf-8'))
sw=(root/'service-worker.js').read_text(encoding='utf-8')

# Schedule: explicit selected-day panel, instead of tapping a date opening New Appointment.
old='''        </article>\n        <article class="panel">\n          <div class="panel-head"><div><h2>Upcoming</h2><p>Tap an appointment to edit it.</p></div></div>\n          <div id="upcomingList" class="appointment-list"></div>\n        </article>'''
new='''        </article>\n        <article class="panel schedule-day-panel" id="selectedDayPanel">\n          <div class="panel-head"><div><h2 id="selectedDayTitle">Selected Day</h2><p id="selectedDaySub">Appointments for this date.</p></div><button type="button" class="primary small" id="addSelectedDayAppointment">+ Add</button></div>\n          <div id="selectedDayList" class="appointment-list"></div>\n        </article>\n        <article class="panel upcoming-panel">\n          <div class="panel-head"><div><h2>Upcoming</h2><p>Your next scheduled cleanings.</p></div></div>\n          <div id="upcomingList" class="appointment-list"></div>\n        </article>'''
if old not in html: raise SystemExit('schedule panel html target missing')
html=html.replace(old,new,1)

# State for selected calendar date.
needle="  let calendarCursor = new Date();\n  calendarCursor.setDate(1);\n"
replace="  let calendarCursor = new Date();\n  calendarCursor.setDate(1);\n  let selectedScheduleDate = todayISO();\n"
if needle not in app: raise SystemExit('calendar cursor target missing')
app=app.replace(needle,replace,1)

# AppLauncher-backed external navigation. Keep a web fallback so Navigate never becomes a dead tap.
old='''  function openExternal(url){\n    try{ if(isAndroidNative()) window.location.href=url; else window.open(url,'_blank','noopener'); }\n    catch{ window.location.href=url; }\n  }\n  function navigateToCustomer(c){\n    const street=String(c?.address||'').trim();\n    if(!street){toast('Add the customer’s street address before navigating.');return;}\n    const address=[street,c?.city,c?.state,c?.zip].map(v=>String(v||'').trim()).filter(Boolean).join(', ');\n    openExternal(`https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=${encodeURIComponent(address)}`);\n  }\n'''
new='''  function openExternal(url){\n    try{const win=window.open(url,'_blank','noopener');if(!win&&location.protocol!=='about:')window.location.href=url;return true;}\n    catch(e){try{window.location.href=url;return true;}catch{return false;}}\n  }\n  async function launchAndroidUrl(nativeUrl,webFallback=''){\n    const launcher=window.Capacitor?.Plugins?.AppLauncher;\n    if(isAndroidNative()&&launcher?.openUrl){\n      try{const result=await launcher.openUrl({url:nativeUrl});if(result?.completed!==false)return true;}catch(e){console.warn('Native app launch failed',e);}\n    }\n    return openExternal(webFallback||nativeUrl);\n  }\n  async function navigateToCustomer(c){\n    const street=String(c?.address||'').trim();\n    if(!street){toast('Add the customer’s street address before navigating.');return false;}\n    const address=[street,c?.city,c?.state,c?.zip].map(v=>String(v||'').trim()).filter(Boolean).join(', ');\n    const web=`https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=${encodeURIComponent(address)}`;\n    toast('Opening Google Maps…');\n    const ok=await launchAndroidUrl(`google.navigation:q=${encodeURIComponent(address)}&mode=d`,web);\n    if(!ok)toast('Could not open navigation. Check that Google Maps or a browser is enabled.');\n    return ok;\n  }\n'''
if old not in app: raise SystemExit('navigation target missing')
app=app.replace(old,new,1)

# Replace the whole schedule renderer with selected-day work/edit visibility.
start=app.index('  function renderSchedule() {')
end=app.index('\n\n  function renderTextCenter()',start)
new_render=r'''  function scheduleAppointmentHtml(a,{showDate=false}={}){
    const c=customerById(a.customerId);
    return `<div class="appointment-row" data-edit-appointment="${a.id}"><div class="appointment-time">${showDate?`${formatDate(a.date,{short:true})}<br><small>${formatTime(a.time)}</small>`:formatTime(a.time)}</div><div class="appointment-meta"><strong>${escapeHtml(customerName(c))}</strong><small>${money(a.price)} · ${escapeHtml(a.notes||'Cleaning')}</small></div><div class="row-actions"><span class="status ${a.status==='completed'?'success':a.status==='cancelled'?'danger':''}">${escapeHtml(a.status)}</span><button class="primary small" type="button" data-active-appointment="${a.id}">Work</button><button class="secondary small" type="button" data-edit-appointment-btn="${a.id}">Edit</button></div></div>`;
  }

  function renderSchedule() {
    const y=calendarCursor.getFullYear(),m=calendarCursor.getMonth();
    $('#calendarTitle').textContent=new Intl.DateTimeFormat('en-US',{month:'long',year:'numeric'}).format(calendarCursor);
    $('#calendarSubtitle').textContent=`${state.appointments.filter(a=>{const d=parseDate(a.date);return d&&d.getFullYear()===y&&d.getMonth()===m&&a.status!=='cancelled'}).length} scheduled cleanings`;
    const first=new Date(y,m,1),start=new Date(y,m,1-first.getDay()),today=todayISO();
    if(!selectedScheduleDate)selectedScheduleDate=today;
    let calHtml='';
    for(let i=0;i<42;i++){
      const d=new Date(start);d.setDate(start.getDate()+i);const iso=isoFromDate(d);
      const apps=state.appointments.filter(a=>a.date===iso&&a.status!=='cancelled').sort((a,b)=>String(a.time||'').localeCompare(String(b.time||'')));
      calHtml+=`<button class="calendar-day ${d.getMonth()!==m?'muted':''} ${iso===today?'today':''} ${iso===selectedScheduleDate?'selected':''}" data-calendar-date="${iso}" type="button"><span class="day-number">${d.getDate()}</span>${apps.slice(0,4).map(a=>`<span class="cal-event" title="${escapeHtml(customerName(customerById(a.customerId)))} ${formatTime(a.time)}"></span>`).join('')}${apps.length>4?`<span class="cal-more">+${apps.length-4}</span>`:''}</button>`;
    }
    $('#calendarGrid').innerHTML=calHtml;

    const selectedApps=state.appointments.filter(a=>a.date===selectedScheduleDate&&a.status!=='cancelled').sort((a,b)=>String(a.time||'').localeCompare(String(b.time||'')));
    $('#selectedDayTitle').textContent=formatDate(selectedScheduleDate);
    $('#selectedDaySub').textContent=selectedApps.length?`${selectedApps.length} cleaning${selectedApps.length===1?'':'s'} scheduled`:'No cleanings scheduled yet.';
    $('#selectedDayList').innerHTML=selectedApps.length?selectedApps.map(a=>scheduleAppointmentHtml(a)).join(''):'<div class="empty">Nothing scheduled for this day. Tap + Add to make an appointment.</div>';

    const upcoming=state.appointments.filter(a=>a.date>=today&&a.status!=='cancelled').sort((a,b)=>`${a.date}T${a.time}`.localeCompare(`${b.date}T${b.time}`)).slice(0,20);
    $('#upcomingList').innerHTML=upcoming.length?upcoming.map(a=>scheduleAppointmentHtml(a,{showDate:true})).join(''):'<div class="empty">No upcoming appointments.</div>';
  }'''
app=app[:start]+new_render+app[end:]

# Scroll every dialog to top when opened; old scroll position was surviving between appointments.
app=app.replace("    $('#deleteAppointmentBtn').hidden=!a; $('#openActiveAppointmentBtn').hidden=!a; $('#appointmentDialog').showModal();\n",
                "    $('#deleteAppointmentBtn').hidden=!a; $('#openActiveAppointmentBtn').hidden=!a; $('#appointmentDialog').showModal();const body=$('#appointmentDialog .dialog-body');if(body)body.scrollTop=0;\n",1)
old="  function openActiveAppointment(id){const a=appointmentById(id);if(!a){toast('That appointment could not be found.');return;}$('#activeAppointmentId').value=a.id;renderWorkMode(a);$('#activeAppointmentDialog').showModal();startWorkTimerLoop();}"
new="  function openActiveAppointment(id){const a=appointmentById(id);if(!a){toast('That appointment could not be found.');return;}$('#activeAppointmentId').value=a.id;$('#activeAppointmentDialog').showModal();const body=$('#activeAppointmentDialog .active-work-body');if(body)body.scrollTop=0;renderWorkMode(a);startWorkTimerLoop();}"
if old not in app: raise SystemExit('open active target missing')
app=app.replace(old,new,1)

# Visible state feedback for job timer buttons.
old="    $('#startCleaningBtn').disabled=!!a.startedAt&&a.status==='in_progress'; $('#finishCleaningBtn').disabled=a.status==='completed'; $('#skipCleaningBtn').disabled=a.status==='completed';\n"
new="    const startBtn=$('#startCleaningBtn');if(startBtn){startBtn.disabled=!!a.startedAt&&a.status==='in_progress';startBtn.textContent=a.status==='in_progress'?'⏱ Cleaning In Progress':'▶ Start Cleaning';} $('#finishCleaningBtn').disabled=a.status==='completed'; $('#skipCleaningBtn').disabled=a.status==='completed';\n"
if old not in app: raise SystemExit('start button render target missing')
app=app.replace(old,new,1)

# Job functions should never silently do nothing.
app=app.replace("  function startCleaning(){const a=appointmentFromDialog();if(!a)return;if(a.status==='completed'){toast('This cleaning is already completed.');return;}if(!a.startedAt)a.startedAt=new Date().toISOString();a.status='in_progress';persistWorkAppointment(a,'Cleaning timer started.');}",
                "  function startCleaning(){const a=appointmentFromDialog();if(!a){toast('Open a saved appointment before starting the cleaning.');return;}if(a.status==='completed'){toast('This cleaning is already completed.');return;}if(!a.startedAt)a.startedAt=new Date().toISOString();a.status='in_progress';persistWorkAppointment(a,'Cleaning timer started.');}")
app=app.replace("  function navigateCurrentAppointment(){const a=appointmentFromDialog();if(a)navigateToCustomer(customerById(a.customerId));}",
                "  async function navigateCurrentAppointment(){const a=appointmentFromDialog();if(!a){toast('Open a saved appointment before navigating.');return;}await navigateToCustomer(customerById(a.customerId));}")

# Calendar taps select a day; + Add is the only create action. Add robust delegated job control dispatcher.
old="      const cal=e.target.closest('[data-calendar-date]'); if(cal){openAppointmentDialog('',cal.dataset.calendarDate);return;}\n"
new="      const cal=e.target.closest('[data-calendar-date]'); if(cal){selectedScheduleDate=cal.dataset.calendarDate;renderSchedule();return;}\n"
if old not in app: raise SystemExit('calendar click target missing')
app=app.replace(old,new,1)

needle="    $('#prevMonth').addEventListener('click',()=>{calendarCursor.setMonth(calendarCursor.getMonth()-1);renderSchedule()}); $('#nextMonth').addEventListener('click',()=>{calendarCursor.setMonth(calendarCursor.getMonth()+1);renderSchedule()}); $('#exportCalendarBtn').addEventListener('click',exportCalendar);\n"
replace=needle+"    $('#addSelectedDayAppointment')?.addEventListener('click',()=>openAppointmentDialog('',selectedScheduleDate||todayISO()));\n"
if needle not in app: raise SystemExit('schedule binding target missing')
app=app.replace(needle,replace,1)

old="    $('#openFullRouteBtn')?.addEventListener('click',openTodayRoute);$('#recalculateTodayRouteBtn')?.addEventListener('click',refreshTodayRoute);$('#navigateAppointmentBtn')?.addEventListener('click',navigateCurrentAppointment);$('#callCustomerBtn')?.addEventListener('click',callCurrentCustomer);$('#onMyWayBtn')?.addEventListener('click',onMyWayCurrent);$('#scheduleLeaveReminderBtn')?.addEventListener('click',scheduleLeaveReminder);$('#arrivedBtn')?.addEventListener('click',arriveAtJob);$('#startCleaningBtn')?.addEventListener('click',startCleaning);$('#finishCleaningBtn')?.addEventListener('click',finishCleaning);$('#skipCleaningBtn')?.addEventListener('click',skipCleaning);$('#addWrittenNoteBtn')?.addEventListener('click',addWrittenNote);\n"
new="""    $('#openFullRouteBtn')?.addEventListener('click',openTodayRoute);$('#recalculateTodayRouteBtn')?.addEventListener('click',refreshTodayRoute);\n    document.addEventListener('click',e=>{\n      const btn=e.target.closest('#navigateAppointmentBtn,#callCustomerBtn,#onMyWayBtn,#scheduleLeaveReminderBtn,#arrivedBtn,#startCleaningBtn,#finishCleaningBtn,#skipCleaningBtn,#addWrittenNoteBtn');\n      if(!btn||btn.disabled)return;\n      e.preventDefault();e.stopPropagation();\n      const actions={navigateAppointmentBtn:navigateCurrentAppointment,callCustomerBtn:callCurrentCustomer,onMyWayBtn:onMyWayCurrent,scheduleLeaveReminderBtn:scheduleLeaveReminder,arrivedBtn:arriveAtJob,startCleaningBtn:startCleaning,finishCleaningBtn:finishCleaning,skipCleaningBtn:skipCleaning,addWrittenNoteBtn:addWrittenNote};\n      try{const result=actions[btn.id]?.();if(result&&typeof result.catch==='function')result.catch(err=>{console.error(`Job control ${btn.id} failed`,err);toast(`Could not complete that job action: ${err?.message||'unknown error'}`);});}catch(err){console.error(`Job control ${btn.id} failed`,err);toast(`Could not complete that job action: ${err?.message||'unknown error'}`);}\n    });\n"""
if old not in app: raise SystemExit('job control direct binding target missing')
app=app.replace(old,new,1)

# Android/Samsung class for a reliable bottom inset even when env(safe-area-inset-bottom) reports 0.
old="    bindEvents();startCloudAutoSync();hydrateCloudLoginFields();\n    if(!isAndroidNative())$$('.android-only').forEach(el=>el.hidden=true);\n"
new="    bindEvents();startCloudAutoSync();hydrateCloudLoginFields();\n    if(isAndroidNative())document.body.classList.add('android-native');else $$('.android-only').forEach(el=>el.hidden=true);\n"
if old not in app: raise SystemExit('init native target missing')
app=app.replace(old,new,1)

# v1.2.6 cache marker.
sw=sw.replace('clear-choice-v125','clear-choice-v126').replace('v1.2.5','v1.2.6')

# S25 Ultra first layout: compact calendar, full-height dialogs, 2x2 action footer, explicit Samsung bottom-nav clearance.
css += r'''

/* v1.2.6 Galaxy S25 Ultra controls + schedule hardening */
.calendar-day.selected{border-color:#6559d8!important;box-shadow:0 0 0 2px rgba(101,89,216,.14)!important;background:#f8f6ff}.calendar-day .cal-event{vertical-align:middle}.cal-more{font-size:9px;font-weight:800;color:var(--muted);margin-left:2px}.schedule-day-panel{margin-top:12px}.schedule-day-panel .panel-head{align-items:center}.schedule-day-panel .appointment-row{cursor:default}
#navigateAppointmentBtn,#callCustomerBtn,#onMyWayBtn,#scheduleLeaveReminderBtn,#arrivedBtn,#startCleaningBtn,#finishCleaningBtn,#skipCleaningBtn,#addWrittenNoteBtn{touch-action:manipulation;-webkit-tap-highlight-color:rgba(101,89,216,.12)}
@media(max-width:760px){
  body.android-native .main{padding-bottom:76px}
  .calendar-panel{padding:10px 8px}.calendar-toolbar{margin-bottom:10px}.calendar-weekdays,.calendar-grid{gap:3px}.calendar-day{min-height:56px;padding:5px}.calendar-day .day-number{font-size:12px}.calendar-day .cal-event{width:6px;height:6px;margin:4px 2px 0 0}.cal-more{font-size:8px}
  #appointmentDialog{width:100vw;height:100dvh;max-height:100dvh;margin:0;border-radius:0}
  #appointmentDialog form{height:100%;max-height:none;min-height:0}
  #appointmentDialog .dialog-head{flex:0 0 auto;padding:14px 16px}
  #appointmentDialog .dialog-body{flex:1 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain;padding:16px 16px 28px}
  #appointmentDialog .dialog-actions{flex:0 0 auto;display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px;padding:10px 12px calc(14px + env(safe-area-inset-bottom));background:#fff;box-shadow:0 -8px 24px rgba(32,34,52,.08);z-index:10}
  body.android-native #appointmentDialog .dialog-actions{padding-bottom:calc(14px + max(env(safe-area-inset-bottom),52px))}
  #appointmentDialog .dialog-actions .spacer{display:none}
  #appointmentDialog .dialog-actions button{width:100%;min-width:0;min-height:48px;padding:8px 9px;white-space:normal;text-align:center;line-height:1.12;font-size:14px}
  .active-work-dialog{height:100dvh!important;max-height:100dvh!important}
  .active-work-dialog>.dialog-head{padding:14px 16px}
  .active-work-dialog>.dialog-body{padding:14px 14px 112px!important;scroll-padding-bottom:112px}
  .active-work-footer{padding:10px 12px calc(14px + env(safe-area-inset-bottom))!important;min-height:72px}
  body.android-native .active-work-footer{padding-bottom:calc(14px + max(env(safe-area-inset-bottom),52px))!important;min-height:120px}
  .active-work-footer button{min-width:0;white-space:normal;line-height:1.15}
  .active-action-grid{grid-template-columns:1fr 1fr}.active-action-grid button{min-height:54px;padding:8px 10px}
  .schedule-day-panel,.upcoming-panel{margin-top:12px}.schedule-day-panel .panel-head{gap:8px}.schedule-day-panel .panel-head button{flex:0 0 auto}
  #selectedDayList .appointment-row,#upcomingList .appointment-row{grid-template-columns:68px minmax(0,1fr)}#selectedDayList .row-actions,#upcomingList .row-actions{padding-left:78px}
}
'''

# Native/package version and Android external-app launcher.
pkg['version']='1.2.6'
pkg.setdefault('dependencies',{})['@capacitor/app-launcher']='latest-7'
(root/'package.json').write_text(json.dumps(pkg,indent=2)+'\n',encoding='utf-8',newline='\n')

# Manifest version label is used by PWA metadata only; native Android versionCode/versionName are patched in workflow.
manifest['version']='1.2.6'
(root/'app.js').write_text(app,encoding='utf-8',newline='\n')
(root/'index.html').write_text(html,encoding='utf-8',newline='\n')
(root/'styles.css').write_text(css,encoding='utf-8',newline='\n')
(root/'service-worker.js').write_text(sw,encoding='utf-8',newline='\n')
(root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8',newline='\n')
print('v126 S25 Ultra controls + schedule patched',len(app),len(html),len(css))
