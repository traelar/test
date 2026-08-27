from pathlib import Path
p=Path('app.js')
s=p.read_text(encoding='utf-8')
old="function stateHasBusinessData(s){return !!((s?.customers?.length||0)||(s?.appointments?.length||0)||(s?.textLog?.length||0)||(s?.settings?.ownerName)||(s?.settings?.ownerPhone)||(s?.settings?.vehicles?.length||0)||(s?.quickNotes));}"
new="function stateHasBusinessData(s){const base=defaultState();return !!((s?.customers?.length||0)||(s?.appointments?.length||0)||(s?.textLog?.length||0)||(s?.quickNotes)||(s?.fuelSnapshot)||JSON.stringify(s?.settings||{})!==JSON.stringify(base.settings));}"
if old not in s:
    raise SystemExit('stateHasBusinessData target not found')
p.write_text(s.replace(old,new,1),encoding='utf-8',newline='\n')
print('all settings now count as protected app data')
