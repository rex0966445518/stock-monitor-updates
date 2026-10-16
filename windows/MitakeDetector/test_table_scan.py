import unittest
from types import SimpleNamespace
from table_scan import scan_table


class TableController:
    def __init__(self,rows=17):
        self.rows=rows;self.position=99;self.scrolls=[];self.stopped=False
    def rect(self):return (0,0,800,600)
    def scroll(self,point,delta):
        self.check();self.scrolls.append(delta)
        self.position=0 if delta>0 else min(self.rows-6,self.position+3)
    def pause(self,delay):self.check()
    def check(self):
        if self.stopped:raise RuntimeError('stopped')
    def image(self,roi):
        self.check();return self.position,None


class TableScanTests(unittest.TestCase):
    def test_stock_batches_arrive_before_whole_ranking_finishes(self):
        controller=TableController();batches=[]
        def observed(rows,bad):batches.append((controller.position,[q.code for q in rows]))
        scan_table(controller,[0,0,1,1],self.read,lambda *s:None,observed=observed)
        self.assertEqual(batches[0][0],0)
        self.assertEqual(len(batches[0][1]),6)
        self.assertGreater(len(batches),1)
        self.assertGreater(batches[-1][0],batches[0][0])
    def read(self,position):
        return [SimpleNamespace(code=str(1000+i),price=position) for i in range(position,position+6)],0

    def test_all_rows_overlap_deduplicate_and_recheck_bottom(self):
        controller=TableController();status=[]
        rows,bad=scan_table(controller,[0,0,1,1],self.read,lambda *s:status.append(s))
        self.assertEqual({q.code for q in rows},{str(1000+i) for i in range(17)})
        self.assertEqual(bad,0);self.assertIn('複查三次',status[-1][2])
        self.assertEqual(controller.scrolls[1:],[-120]*7)

    def test_blank_ocr_three_attempts_never_claims_bottom(self):
        calls=[]
        def read(image):calls.append(image);return [],0
        with self.assertRaisesRegex(RuntimeError,'連續三次'):
            scan_table(TableController(),[0,0,1,1],read,lambda *s:None)
        self.assertEqual(len(calls),3)

    def test_transient_blank_recovers_and_stop_is_respected(self):
        calls=[];controller=TableController(rows=6)
        def read(image):
            calls.append(image)
            return ([],0) if len(calls)==1 else self.read(image)
        rows,_=scan_table(controller,[0,0,1,1],read,lambda *s:None)
        self.assertEqual(len(rows),6)
        controller.stopped=True
        with self.assertRaisesRegex(RuntimeError,'stopped'):
            scan_table(controller,[0,0,1,1],read,lambda *s:None)

    def test_limit_and_nonoverlap_are_incomplete(self):
        with self.assertRaisesRegex(RuntimeError,'保護上限'):
            scan_table(TableController(),[0,0,1,1],self.read,lambda *s:None,max_segments=2)
        controller=TableController()
        with self.assertRaisesRegex(RuntimeError,'沒有重疊'):
            scan_table(controller,[0,0,1,1],lambda n:self.read(n*10),lambda *s:None)


if __name__=='__main__':unittest.main()
