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

    def test_recovery_searches_down_when_upward_search_cannot_find_anchor(self):
        class C(TableController):
            jumped=False
            def scroll(self,point,delta):
                self.scrolls.append(delta)
                if delta>=120*4000:self.position=0
                elif delta>0:self.position=max(0,self.position-3)
                elif self.position==3 and not self.jumped:self.position=0;self.jumped=True
                else:self.position=min(24,self.position+3)
        c=C();messages=[]
        # A transient wrong code set at the unexpected position forces recovery.
        n=[0]
        def read(pos):
            n[0]+=1
            if c.jumped and pos==0:return [SimpleNamespace(code='9999')],0
            return [SimpleNamespace(code=str(1000+i)) for i in range(pos,pos+6)],0
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:messages.append(s[2]))
        self.assertTrue(any('反向向下' in m for m in messages))
        self.assertTrue(any('已找回' in m for m in messages));self.assertTrue(rows)

    def test_invalid_resume_anchor_rebuilds_without_discarding_read_quotes(self):
        from MitakeDetector import Quote
        from dataclasses import asdict
        old=Quote('9999','OLD',100,2,1000,1,1)
        saved=dict(segment=16,codes=['9999'],quotes=[asdict(old)],offset=0,notches=1)
        c=TableController(rows=17);messages=[];checkpoints=[]
        def read(pos):return [Quote(str(1000+i),'TEST',100,2,1000,1,1) for i in range(pos,pos+6)],0
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:messages.append(s[2]),resume=saved,checkpoint=checkpoints.append)
        self.assertEqual({q.code for q in rows},{'9999'}|{str(1000+i) for i in range(17)})
        self.assertTrue(any('自動重建' in m for m in messages))
        self.assertTrue(any(s['codes']==[] and s['quotes'] for s in checkpoints))

    def test_learned_wheel_stride_reads_92_stocks_with_overlap(self):
        class Large(TableController):
            def scroll(self,point,delta):
                self.check();self.scrolls.append(delta)
                self.position=0 if delta>0 else min(92-30,self.position+3*abs(delta)//120)
        def read(position):return [SimpleNamespace(code=str(1000+i)) for i in range(position,position+30)],0
        c=Large();calls=[]
        rows,_=scan_table(c,[0,0,1,1],lambda n:(calls.append(n) or read(n)),lambda *s:None)
        self.assertEqual(len(rows),92)
        self.assertLessEqual(len(calls),8)
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
        self.assertEqual(len(controller.scrolls[1:]),7)
        self.assertTrue(all(delta<0 for delta in controller.scrolls[1:]))

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
        with self.assertRaisesRegex(RuntimeError,'反覆找回|自動回找|反覆失去重疊'):
            scan_table(controller,[0,0,1,1],lambda n:self.read(n*10),lambda *s:None)


if __name__=='__main__':unittest.main()

class LayoutRecoveryTests(unittest.TestCase):
    def test_shift_repairs_same_segment_and_preserves_rows(self):
        c=TableController(rows=17);state={'fixed':False};repairs=[];messages=[]
        roi=[0,0,1,1]
        def read(n):
            if n>=3 and not state['fixed']:return [],6
            return [SimpleNamespace(code=str(1000+i)) for i in range(n,n+6)],0
        def repair():
            repairs.append(c.position);state['fixed']=True;roi[:]=[.1,.1,.9,.9];return True
        rows,bad=scan_table(c,roi,read,lambda *s:messages.append(s[2]),recover_layout=repair)
        self.assertEqual(repairs,[3])
        self.assertEqual({q.code for q in rows},{str(1000+i) for i in range(17)})
        self.assertEqual(bad,0)
        self.assertTrue(any('重讀當前區段' in m for m in messages))
    def test_failed_header_search_does_not_claim_completion(self):
        c=TableController();calls=[]
        with self.assertRaisesRegex(RuntimeError,'連續三次'):
            scan_table(c,[0,0,1,1],lambda image:([],0),lambda *s:None,
                       recover_layout=lambda:calls.append(c.position) or False)
        self.assertEqual(calls,[0])
    def test_repeated_moves_have_bounded_repairs(self):
        c=TableController(rows=40);state={'ready':False};calls=[]
        def repair():state['ready']=True;calls.append(c.position);return True
        def read(n):
            if not state['ready']:return [],0
            state['ready']=False
            return [SimpleNamespace(code=str(1000+i)) for i in range(n,n+6)],0
        with self.assertRaises(RuntimeError):
            scan_table(c,[0,0,1,1],read,lambda *s:None,recover_layout=repair)
        self.assertEqual(len(calls),3)

class PriorityCheckpointTests(unittest.TestCase):
    def test_priority_result_updates_collected_without_skipping_normal_rows(self):
        from MitakeDetector import Quote
        c=TableController(rows=17);seen=[]
        def read(n):return [Quote(str(1000+i),'TEST',100,2,1000,1,100+n) for i in range(n,n+6)],0
        def observed(rows,bad):
            seen.append(rows[0].code)
            if rows[0].code=='1000':return [Quote('1000','TEST',101,3,1100,1,200)]
        rows,bad=scan_table(c,[0,0,1,1],read,lambda *s:None,observed=observed)
        self.assertEqual({q.code for q in rows},{str(1000+i) for i in range(17)})
        self.assertEqual(next(q.ts for q in rows if q.code=='1000'),200)
        self.assertGreater(len(seen),1)

class AutomaticRecoveryTests(unittest.TestCase):
    def test_recovery_uses_latest_checkpoint_without_losing_quotes(self):
        from table_scan import RecoverableScanError,scan_with_recovery
        state=[None];received=[];messages=[]
        checkpoint={'segment':5,'codes':['2330'],'quotes':[{'code':'2330'}]}
        def run(resume):
            received.append(resume)
            if len(received)==1:state[0]=checkpoint;raise RecoverableScanError('temporary OCR failure')
            return ['kept'],0
        self.assertEqual(scan_with_recovery(run,state,lambda:None,messages.append,lambda _:None),(['kept'],0))
        self.assertIs(received[1],checkpoint)
        self.assertIn('保留斷點',messages[0])
    def test_stop_and_unexpected_errors_are_not_retried(self):
        from table_scan import scan_with_recovery
        from autoswitch import AutomationStopped
        for error in [AutomationStopped('F1'),ValueError('bad configuration')]:
            calls=[]
            def run(resume):calls.append(True);raise error
            with self.assertRaises(type(error)):scan_with_recovery(run,[None],lambda:None,lambda _:None,lambda _:None)
            self.assertEqual(len(calls),1)
    def test_recovery_is_bounded_and_user_stop_during_retry_wins(self):
        from table_scan import RecoverableScanError,scan_with_recovery
        from autoswitch import AutomationStopped
        calls=[]
        def run(resume):calls.append(True);raise RecoverableScanError('missing rows')
        with self.assertRaisesRegex(RecoverableScanError,'恢復已達上限'):
            scan_with_recovery(run,[None],lambda:None,lambda _:None,lambda _:None)
        self.assertEqual(len(calls),3)
        calls.clear()
        def pause(_):raise AutomationStopped('F1 pressed')
        with self.assertRaises(AutomationStopped):scan_with_recovery(run,[None],lambda:None,lambda _:None,pause)
        self.assertEqual(len(calls),1)

    def test_old_small_stride_recovers_without_skipping_rows(self):
        class Large(TableController):
            def scroll(self,point,delta):
                self.check();self.scrolls.append(delta)
                self.position=0 if delta>0 else min(170,self.position+3*abs(delta)//120)
        c=Large();positions=[]
        def read(pos):
            positions.append(pos)
            return [SimpleNamespace(code=str(1000+i)) for i in range(pos,pos+30)],0
        rows,_=scan_table(c,[0,0,1,1],read,lambda *s:None,scroll_cap=1)
        self.assertEqual({q.code for q in rows},{str(1000+i) for i in range(200)})
        self.assertLess(len(positions),22)
        self.assertTrue(any(delta<=-120*6 for delta in c.scrolls))

class DeferredPageTests(unittest.TestCase):
    def test_known_codes_with_pending_values_scroll_without_full_page_retries(self):
        c=TableController();calls=[];repair=[];messages=[]
        def read(n):calls.append(n);return [],6
        rows,bad=scan_table(c,[0,0,1,1],read,lambda *args:messages.append(args[-1]),
            read_anchors=lambda n:[str(1000+i) for i in range(n,n+6)],
            recover_layout=lambda:repair.append(True),deferred_view=lambda:True)
        self.assertEqual(rows,[]);self.assertGreater(bad,0);self.assertEqual(repair,[])
        self.assertEqual(calls[:3],[0,3,6]);self.assertTrue(any('待補' in m for m in messages))

