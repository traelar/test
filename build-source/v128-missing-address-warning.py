from pathlib import Path
import json
root=Path('.')
app=(root/'app.js').read_text(encoding='utf-8')
pkg=json.loads((root/'package.json').read_text(encoding='utf-8'))
sw=(root/'service-worker.js').read_text(encoding='utf-8')

if "title:'No Address Saved'" not in app:
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
    if(!city||!region||!zip){toast('This customer’s address is incomplete. Navigation will use the address information that is saved.');}
    const address=[street,city,region,zip].filter(Boolean).join(', ');
    const web=`https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate&destination=${encodeURIComponent(address)}`;
    toast('Opening Google Maps…');
    const ok=await launchAndroidUrl(`google.navigation:q=${encodeURIComponent(address)}&mode=d`,web);
    if(!ok)toast('Could not open navigation. Check that Google Maps or a browser is enabled.');
    return ok;
  }
"""
    if old not in app: raise SystemExit('navigateToCustomer target missing')
    app=app.replace(old,new,1)
else:
    print('missing-address warning already present; keeping it')

pkg['version']='1.2.8'
sw=sw.replace('clear-choice-v127','clear-choice-v128').replace('v1.2.7','v1.2.8')
(root/'app.js').write_text(app,encoding='utf-8',newline='\n')
(root/'package.json').write_text(json.dumps(pkg,indent=2)+'\n',encoding='utf-8',newline='\n')
(root/'service-worker.js').write_text(sw,encoding='utf-8',newline='\n')
print('patched v1.2.8 missing-address warning')
