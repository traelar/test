from pathlib import Path
p = Path('styles.css')
p.write_text(p.read_text().rstrip('\r\n'))
