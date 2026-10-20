"""Transparent condition counts; ranking never promotes an unverified row to a hit."""

def assess(row,cfg):
    q,ratio,reason,hit=row
    checks=[('股價',q.price>=cfg['min_price'],min(1,q.price/max(cfg['min_price'],.0001))),
            ('總量',q.volume>=cfg['min_volume'],min(1,q.volume/max(cfg['min_volume'],.0001)))]
    low,high=cfg['min_gain'],cfg['max_gain']
    distance=max(low-q.gain,0,q.gain-high)
    checks.append(('漲幅',low<=q.gain<=high,max(0,1-distance/max(1,high-low))))
    if cfg['use_turnover']:
        checks.append(('換手率',q.turnover is not None and q.turnover>=cfg['min_turnover'],0 if q.turnover is None else min(1,q.turnover/max(cfg['min_turnover'],.0001))))
    if cfg['use_accel']:
        checks.append(('量速',ratio is not None and ratio>=cfg['accel'],0 if ratio is None else min(1,ratio/max(cfg['accel'],.0001))))
    if cfg['use_breakout']:
        passed=hit or not any(t in reason for t in ['等待觀測突破守穩','等待重新複查','讀值跳動','量倒退','等待第二次讀值'])
        checks.append(('突破守穩',passed,float(passed)))
    count=sum(bool(passed) for _,passed,_ in checks);total=len(checks)
    blocked=any(t in reason for t in ['等待重新複查','等待接續複查','掃描讀值已過期','60秒讀值未變','讀值跳動','量倒退'])
    if blocked:label='待複查'
    elif '已轉弱' in reason:label='已轉弱'
    elif '盤尾緩漲未通過' in reason:label='待盤尾檢核'
    elif '進場檢核未通過' in reason:label='等待進場檢核'
    elif hit:label='★ 完全符合'
    elif count>=total-1:label='接近候選'
    else:label='觀察中'
    missing=[name for name,passed,_ in checks if not passed]
    if '盤尾緩漲未通過' in reason:missing.append('盤尾緩漲檢核')
    if count==total and not hit and not blocked:missing.append('有效複查')
    return dict(count=count,total=total,hit=hit and not blocked,label=label,
                text=f'{label} {count}/{total}',missing=missing,
                key=(-count,not (hit and not blocked),blocked,-sum(c for _,_,c in checks),-q.gain,q.code))


def ordered_candidates(rows,cfg):
    return sorted(((row,assess(row,cfg)) for row in rows),key=lambda pair:pair[1]['key'])


def name_eligible(match):
    return match['count'] >= min(5,match['total'])


def hidden_result(q,cfg,filters):
    return bool((filters.get('gain') and not cfg['min_gain']<=q.gain<=cfg['max_gain'])
                or (filters.get('price') and q.price<cfg['min_price'])
                or (filters.get('turnover') and cfg['use_turnover']
                    and (q.turnover is None or q.turnover<cfg['min_turnover'])))
