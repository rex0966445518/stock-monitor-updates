"""Near-entry voice alerts are distinct from fully verified hit alerts."""
from candidate_ranking import assess


def near_alerts(rows,cfg,last):
    result=[]
    for row in rows:
        q,ratio,reason,hit=row
        match=assess(row,cfg)
        if hit or match['total']!=6 or match['count']!=5 or match['label']=='待複查':continue
        if q.ts-last.get(q.code,-1e20)<cfg['cooldown']:continue
        last[q.code]=q.ts
        result.append(q)
    return result


def voice_text(quotes):
    text='；'.join(f'{q.name or "股名待辨識"}，股號 {q.code}，已接近上車條件，請注意' for q in quotes[:3])
    if len(quotes)>3:text+=f'；另有{len(quotes)-3}檔接近條件，請查看清單'
    return text
