"""Near-entry voice alerts are distinct from fully verified hit alerts."""
from candidate_ranking import assess


def near_alerts(rows,cfg,last):
    result=[]
    for row in rows:
        q,ratio,reason,hit=row
        match=assess(row,cfg)
        if hit or match['total']!=6 or match['count']!=5 or match['label'] in ('待複查','已轉弱'):continue
        if q.ts-last.get(q.code,-1e20)<cfg['cooldown']:continue
        last[q.code]=q.ts
        result.append(q)
    return result


def voice_text(quotes,rows=None,cfg=None):
    details={row[0].code:assess(row,cfg)['missing'] for row in rows or []} if cfg else {}
    for row in rows or []:
        if '進場檢核未通過' in row[2]:details.setdefault(row[0].code,[]).append('進場檢核')
    text='；'.join(f'{q.name or "股名待辨識"}，股號 {q.code}，觀察候選五項符合，尚缺'+('、'.join(details.get(q.code,[])) or '有效複查')+'，請等待確認' for q in quotes[:3])
    if len(quotes)>3:text+=f'；另有{len(quotes)-3}檔觀察候選，請查看清單'
    return text
