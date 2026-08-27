from pathlib import Path
root=Path('.')
app=(root/'app.js').read_text(encoding='utf-8')
html=(root/'index.html').read_text(encoding='utf-8')
css=(root/'styles.css').read_text(encoding='utf-8')

old = '''          <div class="segmented" id="textConsentSegment">
            <label><input type="radio" name="textConsent" value="opted_in" /><span>✓ Opted In</span></label>
            <label><input type="radio" name="textConsent" value="not_asked" checked /><span>Not Asked</span></label>
            <label><input type="radio" name="textConsent" value="opted_out" /><span>⛔ Opted Out</span></label>
          </div>'''
new = '''          <input type="hidden" id="customerTextConsent" value="not_asked" />
          <div class="consent-choice-grid" id="textConsentSegment" role="group" aria-label="Text message permission">
            <button type="button" class="consent-choice" data-consent-choice="opted_in">✓ Opted In</button>
            <button type="button" class="consent-choice active" data-consent-choice="not_asked">Not Asked</button>
            <button type="button" class="consent-choice danger-choice" data-consent-choice="opted_out">⛔ Opted Out</button>
          </div>'''
if old not in html:
    raise SystemExit('consent html target missing')
html = html.replace(old, new, 1)

old = '''    const consent=c?.textConsent||'not_asked'; const radio=$(`input[name="textConsent"][value="${consent}"]`); if(radio)radio.checked=true;
'''
new = '''    const consent=c?.textConsent||'not_asked';const consentValue=$('#customerTextConsent');if(consentValue)consentValue.value=consent;$$('[data-consent-choice]').forEach(btn=>btn.classList.toggle('active',btn.dataset.consentChoice===consent));
'''
if old not in app:
    raise SystemExit('open customer consent target missing')
app = app.replace(old, new, 1)

old = '''    const id=$('#customerId').value||uid(); const existing=customerById(id); const oldConsent=existing?.textConsent||'not_asked'; const newConsent=$('input[name="textConsent"]:checked')?.value||'not_asked';
'''
new = '''    const id=$('#customerId').value||uid(); const existing=customerById(id); const oldConsent=existing?.textConsent||'not_asked'; const newConsent=$('#customerTextConsent')?.value||'not_asked';
'''
if old not in app:
    raise SystemExit('save customer consent target missing')
app = app.replace(old, new, 1)

old = '''    $('input[name="textConsent"]')?.form?.addEventListener('change',e=>{if(e.target.name==='textConsent'&&!$('#customerConsentDate').value)$('#customerConsentDate').value=todayISO();});
'''
new = '''    $('#textConsentSegment')?.addEventListener('click',e=>{const btn=e.target.closest('[data-consent-choice]');if(!btn)return;e.preventDefault();const value=btn.dataset.consentChoice||'not_asked';const hidden=$('#customerTextConsent');if(hidden)hidden.value=value;$$('[data-consent-choice]').forEach(b=>b.classList.toggle('active',b===btn));if(value!=='not_asked'&&!$('#customerConsentDate').value)$('#customerConsentDate').value=todayISO();});
'''
if old not in app:
    raise SystemExit('consent event target missing')
app = app.replace(old, new, 1)

css += '''
/* v1.3.0 consent crash hardening */
.consent-choice-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.consent-choice{min-height:48px;border:1px solid var(--border);border-radius:12px;background:var(--panel-2);color:var(--muted);font-weight:800;padding:8px 10px}.consent-choice.active{color:#fff;border-color:rgba(56,216,177,.6);background:linear-gradient(135deg,rgba(25,151,117,.94),rgba(37,195,155,.82));box-shadow:0 0 0 2px rgba(56,216,177,.12)}.consent-choice.danger-choice.active{border-color:rgba(255,91,125,.62);background:linear-gradient(135deg,rgba(176,45,80,.96),rgba(218,61,96,.84));box-shadow:0 0 0 2px rgba(255,91,125,.12)}#textConsentSegment{touch-action:manipulation;-webkit-tap-highlight-color:transparent}@media(max-width:760px){.consent-choice-grid{grid-template-columns:1fr}.consent-choice{min-height:52px;font-size:15px}}
'''

(root/'app.js').write_text(app,encoding='utf-8',newline='\n')
(root/'index.html').write_text(html,encoding='utf-8',newline='\n')
(root/'styles.css').write_text(css,encoding='utf-8',newline='\n')
print('patched v1.3.0 consent crash hardening')
