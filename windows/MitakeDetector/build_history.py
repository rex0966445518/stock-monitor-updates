"""Append current build time without inventing a publication timestamp."""
import datetime as dt
import json
from pathlib import Path
from build_release import version


if __name__=='__main__':
    path=Path('version_history.json');rows=json.loads(path.read_text(encoding='utf-8'))
    current=version();rows=[r for r in rows if r['version']!=current]
    rows.append(dict(version=current,built_at=dt.datetime.now(dt.timezone.utc).isoformat(),notes=Path('RELEASE_NOTES.md').read_text(encoding='utf-8')))
    path.write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
