from pathlib import Path

# Cloud/local browser protection: every non-default setting counts as real app data.
p=Path('app.js')
s=p.read_text(encoding='utf-8')
old="function stateHasBusinessData(s){return !!((s?.customers?.length||0)||(s?.appointments?.length||0)||(s?.textLog?.length||0)||(s?.settings?.ownerName)||(s?.settings?.ownerPhone)||(s?.settings?.vehicles?.length||0)||(s?.quickNotes));}"
new="function stateHasBusinessData(s){const base=defaultState();return !!((s?.customers?.length||0)||(s?.appointments?.length||0)||(s?.textLog?.length||0)||(s?.quickNotes)||(s?.fuelSnapshot)||JSON.stringify(s?.settings||{})!==JSON.stringify(base.settings));}"
if old not in s:
    raise SystemExit('stateHasBusinessData target not found')
p.write_text(s.replace(old,new,1),encoding='utf-8',newline='\n')

# Windows rolling backups: any settings object is valuable, even with zero customers.
p=Path('electron-main.cjs')
s=p.read_text(encoding='utf-8')
old="function looksPopulated(raw){try{const s=JSON.parse(raw||'{}');return !!((s.customers||[]).length||(s.appointments||[]).length||(s.textLog||[]).length||s.quickNotes||s.settings?.ownerName||s.settings?.ownerPhone||(s.settings?.vehicles||[]).length);}catch{return false}}"
new="function looksPopulated(raw){try{const s=JSON.parse(raw||'{}');return !!((s.customers||[]).length||(s.appointments||[]).length||(s.textLog||[]).length||s.quickNotes||s.fuelSnapshot||(s.settings&&Object.keys(s.settings).length));}catch{return false}}"
if old not in s:
    raise SystemExit('looksPopulated target not found')
p.write_text(s.replace(old,new,1),encoding='utf-8',newline='\n')
print('all settings now count as protected and backup-worthy app data')
