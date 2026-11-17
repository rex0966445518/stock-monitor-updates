"""Generate offline preview from the same assets shipped to the Android project."""
from pathlib import Path
import base64, re
root=Path(__file__).resolve().parents[1]
old=(root/'TaiwanForecast-UI-Preview.html').read_text()
fixture=old[old.rfind('<script>\nsnap='):old.rfind('</html>')]
s=(root/'assets/index.html').read_text()
s=re.sub(r'<meta http-equiv="Content-Security-Policy"[^>]*>','',s)
s=s.replace('<header>','<div class="demo-banner">設計預覽 · 所有數字皆為示意資料，非即時行情</div><header>',1)
s=s.replace('src="app-icon.png"','src="data:image/png;base64,'+base64.b64encode((root/'assets/app-icon.png').read_bytes()).decode()+'"')
def inline(m):
    code=(root/'assets'/m[1]).read_text()
    if m[1]=='ui.js':
        code=re.sub(r'try\{snap=JSON.parse\(localStorage.*?catch\{snap=null\}', '', code)
        code=code.replace("refresh();setInterval(tick,15000);document.addEventListener('visibilitychange',tick);",'')
    return '<script>'+code+'</script>'
s=re.sub(r'<script src="([^"]+)"></script>',inline,s)
s=s.replace('</html>',fixture+'</html>')
(root/'TaiwanForecast-UI-Preview.html').write_text(s)
