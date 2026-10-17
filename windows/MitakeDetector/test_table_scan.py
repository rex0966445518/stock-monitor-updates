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
    def test_overshoot_scrolls_back_finds_overlap_and_finishes_all_rows(self):
        class Controller(TableController):
            jumped=False
            def scroll(self,point,delta):
                self.check();self.scrolls.append(delta)
                if delta>=120*4000:self.position=0
                elif delta>0:self.position=max(0,self.position-3)
                else:
                    movement=3*abs(delta)//120
                    if abs(delta)>120 and not self.jumped:movement=60;self.jumped=True
                    self.position=min(70,self.position+movement)
        c=Controller();learned=[];messages=[]
        read=lambda n:([SimpleNamespace(code=str(1000+i)) for i in range(n,n+30)],0)
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:messages.append(s[2]),learn_scroll=lambda *s:learned.append(s))
        self.assertEqual({q.code for q in rows},{str(1000+i) for i in range(100)})
        self.assertTrue(any('已找回重疊' in message for message in messages))
        self.assertTrue(any(recovered and cap<=3 for cap,recovered in learned))
        self.assertGreater(sum(0<d<480000 for d in c.scrolls),0)
    def test_overlap_uses_codes_even_when_other_fields_are_incomplete(self):
        c=TableController(rows=17)
        # Only two complete quotes per view, but the visible code column has six.
        read=lambda n:([SimpleNamespace(code=str(1000+i)) for i in range(n,n+2)],0)
        anchors=lambda n:[str(1000+i) for i in range(n,n+6)]
        messages=[]
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:messages.append(s[2]),read_anchors=anchors)
        self.assertTrue(rows)
        self.assertIn('推定清單末端',messages[-1])
        self.assertFalse(any('回找' in m for m in messages))

    def test_learned_wheel_stride_reads_92_stocks_with_overlap(self):
        class Large(TableController):
            def scroll(self,point,delta):
                self.check();self.scrolls.append(delta)
                self.position=0 if delta>0 else min(92-30,self.position+3*abs(delta)//120)
        def read(position):return [SimpleNamespace(code=str(1000+i)) for i in range(position,position+30)],0
        c=Large();calls=[]
        rows,_=scan_table(c,[0,0,1,1],lambda n:(calls.append(n) or read(n)),lambda *s:None)
        self.assertEqual(len(rows),92)
        self.assertLessEqual(len(calls),10)
        self.assertTrue(any(delta < -120 for delta in c.scrolls))
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
        with self.assertRaisesRegex(RuntimeError,'反覆找回|自動回找'):
            scan_table(controller,[0,0,1,1],lambda n:self.read(n*10),lambda *s:None)


if __name__=='__main__':unittest.main()
