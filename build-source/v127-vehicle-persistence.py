from pathlib import Path
import json
root=Path('.')
app=(root/'app.js').read_text(encoding='utf-8')
pkg=json.loads((root/'package.json').read_text(encoding='utf-8'))
sw=(root/'service-worker.js').read_text(encoding='utf-8')

# New vehicles carry an entity timestamp so cloud merge can preserve them independently.
old="createdAt:new Date().toISOString()};state.settings.vehicles="
new="createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()};state.settings.vehicles="
if old not in app: raise SystemExit('vehicle creation target missing')
app=app.replace(old,new,1)

# Merge saved vehicles record-by-record and keep a valid current/default vehicle.
old="""    merged.customers=mergeEntityList(local.customers,remote.customers,lt,rt,merged.cloudTombstones.customers);\n    merged.appointments=mergeEntityList(local.appointments,remote.appointments,lt,rt,merged.cloudTombstones.appointments).filter(a=>!merged.cloudTombstones.customers?.[a.customerId]);\n    merged.textLog=mergeTextLog(local.textLog,remote.textLog,merged.cloudTombstones.customers);\n    merged.cloudRecovery=mergeRecovery(local.cloudRecovery,remote.cloudRecovery);\n    return merged;\n"""
new="""    merged.customers=mergeEntityList(local.customers,remote.customers,lt,rt,merged.cloudTombstones.customers);\n    merged.appointments=mergeEntityList(local.appointments,remote.appointments,lt,rt,merged.cloudTombstones.appointments).filter(a=>!merged.cloudTombstones.customers?.[a.customerId]);\n    const localVehicles=Array.isArray(local.settings?.vehicles)?local.settings.vehicles:[],remoteVehicles=Array.isArray(remote.settings?.vehicles)?remote.settings.vehicles:[];\n    const mergedVehicles=mergeEntityList(localVehicles,remoteVehicles,lt,rt,{});\n    merged.settings={...(merged.settings||{}),vehicles:mergedVehicles};\n    const localActive=String(local.settings?.activeVehicleId||''),remoteActive=String(remote.settings?.activeVehicleId||''),newerActive=lt>=rt?localActive:remoteActive,olderActive=lt>=rt?remoteActive:localActive;\n    const validActive=id=>!!id&&mergedVehicles.some(v=>String(v.id)===id);\n    merged.settings.activeVehicleId=validActive(newerActive)?newerActive:validActive(olderActive)?olderActive:(mergedVehicles[0]?.id||'');\n    merged.textLog=mergeTextLog(local.textLog,remote.textLog,merged.cloudTombstones.customers);\n    merged.cloudRecovery=mergeRecovery(local.cloudRecovery,remote.cloudRecovery);\n    return merged;\n"""
if old not in app: raise SystemExit('cloud vehicle merge target missing')
app=app.replace(old,new,1)

# Vehicle selected while working a job becomes both the appointment vehicle and the current/default vehicle.
old="""  function syncActiveWorkInputs(a){if(!a||!$('#activeAppointmentDialog')?.open)return;a.miles=numberOrZero($('#activeAppointmentMiles').value);a.expenses=numberOrZero($('#activeAppointmentExpenses').value);a.vehicleId=$('#activeAppointmentVehicle').value||state.settings.activeVehicleId||'';a.fuelPrice=numberOrZero($('#activeAppointmentFuelPrice').value)||numberOrZero(state.fuelSnapshot?.price);a.updatedAt=new Date().toISOString();}\n"""
new="""  function syncActiveWorkInputs(a){if(!a||!$('#activeAppointmentDialog')?.open)return;a.miles=numberOrZero($('#activeAppointmentMiles').value);a.expenses=numberOrZero($('#activeAppointmentExpenses').value);const selectedVehicle=$('#activeAppointmentVehicle').value||state.settings.activeVehicleId||'';a.vehicleId=selectedVehicle;if(selectedVehicle&&vehicleById(selectedVehicle))state.settings.activeVehicleId=selectedVehicle;a.fuelPrice=numberOrZero($('#activeAppointmentFuelPrice').value)||numberOrZero(state.fuelSnapshot?.price);a.updatedAt=new Date().toISOString();}\n"""
if old not in app: raise SystemExit('active appointment vehicle target missing')
app=app.replace(old,new,1)

# Vehicle selected in appointment editor also becomes the current/default vehicle.
old="""    const a={...(existing||{}),id,customerId,date:$('#appointmentDate').value,time:$('#appointmentTime').value,price:Number($('#appointmentPrice').value)||0,status:$('#appointmentStatus').value,payment:$('#appointmentPayment').value,reminder:$('#appointmentReminder').value,miles:numberOrZero($('#appointmentMiles').value),expenses:numberOrZero($('#appointmentExpenses').value),vehicleId:$('#appointmentVehicle').value||state.settings.activeVehicleId||'',fuelPrice:numberOrZero($('#appointmentFuelPrice').value)||numberOrZero(state.fuelSnapshot?.price),notes:$('#appointmentNotes').value.trim(),updatedAt:new Date().toISOString()};\n"""
new="""    const a={...(existing||{}),id,customerId,date:$('#appointmentDate').value,time:$('#appointmentTime').value,price:Number($('#appointmentPrice').value)||0,status:$('#appointmentStatus').value,payment:$('#appointmentPayment').value,reminder:$('#appointmentReminder').value,miles:numberOrZero($('#appointmentMiles').value),expenses:numberOrZero($('#appointmentExpenses').value),vehicleId:$('#appointmentVehicle').value||state.settings.activeVehicleId||'',fuelPrice:numberOrZero($('#appointmentFuelPrice').value)||numberOrZero(state.fuelSnapshot?.price),notes:$('#appointmentNotes').value.trim(),updatedAt:new Date().toISOString()};\n    if(a.vehicleId&&vehicleById(a.vehicleId))state.settings.activeVehicleId=a.vehicleId;\n"""
if old not in app: raise SystemExit('appointment editor vehicle target missing')
app=app.replace(old,new,1)

# Current vehicle selector saves immediately and stamps the selected vehicle.
old="$('#activeVehicle')?.addEventListener('change',e=>{state.settings.activeVehicleId=e.target.value;saveState();});"
new="$('#activeVehicle')?.addEventListener('change',e=>{state.settings.activeVehicleId=e.target.value;const v=vehicleById(e.target.value);if(v)v.updatedAt=new Date().toISOString();saveState();toast(v?`${v.year} ${v.make} ${v.model} set as your current vehicle.`:'Current vehicle cleared.');});"
if old not in app: raise SystemExit('current vehicle binding target missing')
app=app.replace(old,new,1)

# Navigation must explain missing/incomplete addresses instead of looking like a dead button.
old="""  async function navigateToCustomer(c){
    const street=String(c?.address||'').trim();
    if(!street){toast('Add the customer’s street address before navigating.');return false;}
    const address=[street,c?.city,c?.state,c?.zip].map(v=>String(v||'').trim()).filter(Boolean).join(', ');
    const web=`https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=${encodeURIComponent(address)}`;
    toast('Opening Google Maps…');
    const ok=await launchAndroidUrl(`google.navigation:q=${encodeURIComponent(address)}&mode=d`,web);
    if(!ok)toast('Could not open navigation. Check that Google Maps or a browser is enabled.');
    return ok;
  }
"""
new="""  async function navigateToCustomer(c){
    if(!c){toast('Customer record could not be found.');return false;}
    const street=String(c.address||'').trim();
    const city=String(c.city||'').trim(),region=String(c.state||'').trim(),zip=String(c.zip||'').trim();
    if(!street){
      const edit=await askConfirm(`No street address is saved for ${customerName(c)}. Add the customer’s full address before using Navigate.`,{title:'No Address Saved',confirmText:'Edit Customer'});
      if(edit){try{$('#activeAppointmentDialog')?.close();}catch{}try{$('#appointmentDialog')?.close();}catch{}openCustomerDialog(c.id);}
      return false;
    }
    if(!city||!region||!zip)toast('This customer’s address is incomplete. Navigation will use the address information that is saved.');
    const address=[street,city,region,zip].filter(Boolean).join(', ');
    const web=`https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=${encodeURIComponent(address)}`;
    toast('Opening Google Maps…');
    const ok=await launchAndroidUrl(`google.navigation:q=${encodeURIComponent(address)}&mode=d`,web);
    if(!ok)toast('Could not open navigation. Check that Google Maps or a browser is enabled.');
    return ok;
  }
"""
if old not in app: raise SystemExit('navigate address-warning target missing')
app=app.replace(old,new,1)

pkg['version']='1.2.7'
sw=sw.replace('clear-choice-v126','clear-choice-v127').replace('v1.2.6','v1.2.7')
(root/'app.js').write_text(app,encoding='utf-8',newline='\n')
(root/'package.json').write_text(json.dumps(pkg,indent=2)+'\n',encoding='utf-8',newline='\n')
(root/'service-worker.js').write_text(sw,encoding='utf-8',newline='\n')
print('patched v1.2.7 vehicle persistence + address warning')
