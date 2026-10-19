"""Keep stocks that reached at least five enabled conditions until restart."""
from candidate_ranking import assess
import datetime as dt


class RetainedCandidates:
    def __init__(self):
        self.rows={}
        self.peak={}

    def merge(self,rows,cfg):
        current={row[0].code:row for row in rows}
        for code,row in current.items():
            match=assess(row,cfg)
            if match['count']>=5 and match['label']!='待複查':
                self.peak[code]=max(self.peak.get(code,0),match['count'])
            if code in self.peak:
                previous=self.rows.get(code)
                if previous is None or row[0].ts>=previous[0].ts:self.rows[code]=row
        merged=dict(current)
        for code,row in self.rows.items():
            if code in current:continue
            q,ratio,reason,hit=row
            merged[code]=(q,ratio,f'保留候選｜等待重新複查｜最後讀取 {dt.datetime.fromtimestamp(q.ts):%H:%M:%S}｜'+reason,False)
        return list(merged.values())
