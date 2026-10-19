"""Experimental observed-price entry checks, separate from the six base filters."""

def evaluate(history,prior_high,holds,cfg,now):
    q=history[-1];code=q.code
    window=cfg.get('guard_window',60);max_gap=cfg.get('guard_max_gap',30)
    required_hold=cfg.get('guard_hold',30)
    state=holds.get(code)
    previous=history[-2] if len(history)>1 else None
    if state and (q.price<state[0] or q.ts-state[2]>max_gap):state=None
    if state is None and previous and q.price>prior_high:state=[q.price,q.ts,q.ts]
    if state:state[2]=q.ts;holds[code]=state
    else:holds.pop(code,None)
    reasons=[];weak=False
    if now-q.ts>cfg.get('guard_max_age',15):reasons.append('掃描讀值已過期')
    if q.ts-history[0].ts<cfg.get('guard_warmup',180):reasons.append('觀測暖機不足')
    samples=[p for p in history if q.ts-p.ts<=window+max_gap]
    baseline=min(samples,key=lambda p:abs(p.ts-(q.ts-window)))
    if abs(baseline.ts-(q.ts-window))>max_gap or len(samples)<3:
        reasons.append('短線方向資料不足')
    else:
        samples=[p for p in samples if p.ts>=baseline.ts]
        if any(b.ts-a.ts>max_gap for a,b in zip(samples,samples[1:])):reasons.append('觀測間隔過長')
        rise=(q.price/baseline.price-1)*100
        up=down=0
        for a,b in zip(samples,samples[1:]):
            volume=max(0,b.volume-a.volume)
            if b.price>a.price:up+=volume
            elif b.price<a.price:down+=volume
        if q.price<baseline.price or (previous and q.price<previous.price):weak=True
        if previous and q.price<previous.price:reasons.append('最新價格回落')
        if rise<cfg.get('guard_min_rise',.2):reasons.append('短線未持續向上')
        if up<=down:reasons.append('上漲區間量未大於下跌區間量')
    high=max(prior_high,q.price)
    pullback=(1-q.price/high)*100
    if pullback>cfg.get('guard_max_pullback',1):reasons.append('距觀測高點回落過大');weak=True
    if not state or q.ts-state[1]<required_hold:reasons.append('觀測突破尚未持續守穩')
    return reasons,weak
