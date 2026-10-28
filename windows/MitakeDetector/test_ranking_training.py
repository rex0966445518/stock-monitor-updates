import unittest
from ranking_training import Progress

class TrainingTests(unittest.TestCase):
    def test_round_covers_all_without_duplicates(self):
        p=Progress(['A','B','C'])
        for _ in range(30):self.assertEqual(sorted(p.round()),['A','B','C'])
    def test_every_rank_needs_three_verified_successes(self):
        p=Progress(['A','B'])
        for _ in range(3):p.result('A',True)
        self.assertFalse(p.complete)
        for _ in range(3):p.result('B',True)
        self.assertTrue(p.complete)
    def test_failure_resets_streak_not_history(self):
        p=Progress(['A']);p.result('A',True);p.result('A',True);p.result('A',False)
        self.assertEqual(p.streak['A'],0)
        self.assertEqual(p.attempts['A'],3);self.assertEqual(p.failures['A'],1)
        p.result('A',True);self.assertFalse(p.complete)
    def test_assistance_alone_cannot_pass(self):
        p=Progress(['A']);p.result('A',False)
        self.assertFalse(p.complete);self.assertEqual(p.streak['A'],0)

if __name__=='__main__':unittest.main()


class TeachingReplayTests(unittest.TestCase):
    def test_reopens_popup_and_replays_each_scroll_even_if_marked_open(self):
        from unittest.mock import Mock
        from ranking_training import replay_menu
        c=Mock();c.rect.return_value=(0,0,1000,800)
        image=Mock();image.size=(200,400)
        region=dict(left=0,top=30,width=200,height=400)
        c.image.return_value=(image,region)
        s=Mock(c=c,combo=[0,0,.2,.03],menu=[0,.04,.2,.5],menu_is_open=True)
        self.assertEqual(replay_menu(s,2),(image,region))
        self.assertEqual(c.u.keybd_event.call_count,2)
        c.click.assert_called_once()
        self.assertEqual([call.args[1] for call in c.scroll.call_args_list],[7200,-120,-120])
        c.activate.assert_called_once()

    def test_learning_window_never_withdraws(self):
        import inspect
        from ranking_training import open_training
        source=inspect.getsource(open_training)
        self.assertNotIn('top.withdraw(',source)
        self.assertNotIn('top.iconify(',source)

class TeachingMemoryTests(unittest.TestCase):
    def test_confirmed_candidate_persists_but_does_not_pass_test(self):
        import tempfile
        from pathlib import Path
        from ranking_training import TeachingMemory
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'positions.json';memory=TeachingMemory(path,'layout-a')
            memory.remember('買氣強大',35,0,(50,70),(200,300))
            saved=TeachingMemory(path,'layout-a').get('買氣強大')
            self.assertEqual(saved['number'],35);self.assertEqual(saved['point'],[50,70])
            self.assertIsNone(TeachingMemory(path,'layout-b').get('買氣強大'))
            self.assertFalse(Progress(['買氣強大']).complete)
    def test_bad_positions_do_not_drive_mouse(self):
        import tempfile
        from pathlib import Path
        from ranking_training import TeachingMemory
        with tempfile.TemporaryDirectory() as d:
            m=TeachingMemory(Path(d)/'p.json','a')
            for point in [(float('nan'),20),(-1,20),(201,20)]:
                with self.assertRaises(ValueError):m.remember('A',1,0,point,(200,300))
            self.assertIsNone(m.get('A'))

class TeachingButtonsTests(unittest.TestCase):
    def test_arrows_preview_only_and_confirm_saves_position(self):
        import tempfile,time,types,threading
        from pathlib import Path
        from unittest.mock import Mock,patch
        from PIL import Image
        from ranking_training import open_training,TeachingMemory
        buttons={};scheduled=[];created=[]
        class Widget:
            def __init__(self,*args,**kw):
                self.bindings={};self.selected='';self.value=kw.get('value','');self.items={};self.columns=kw.get('columns');created.append(self)
                if 'command' in kw and 'text' in kw:buttons[kw['text']]=kw['command']
            def bind(self,event,callback):self.bindings[event]=callback
            def identify_row(self,y):return self.selected
            def insert(self,parent,index,**kw):self.items[kw['iid']]=kw
            def item(self,key,**kw):self.items[key].update(kw);return self.items[key]
            def __getattr__(self,name):return lambda *a,**k:None
            def __setitem__(self,key,value):pass
            def get(self):return self.value
            def set(self,value):self.value=value
            def winfo_screenwidth(self):return 1600
            def winfo_screenheight(self):return 720
            def winfo_width(self):return 640
            def winfo_height(self):return 240
            def winfo_exists(self):return True
            def after(self,ms,callback):scheduled.append(callback)
        widgets=types.SimpleNamespace(**{n:Widget for n in ('Toplevel','StringVar','Label','Treeview','Canvas','Frame','Button','Combobox','Scrollbar')})
        with tempfile.TemporaryDirectory() as d:
            app=Mock();app.root=Widget();app.tk=widgets;app.ttk=widgets;app.status=Widget()
            app.idle.return_value=True;app.dependencies.return_value=True;app.generation=0
            app.config={'auto_config':{'combo':[0,0,.2,.1],'menu':[0,.1,.2,.5]}}
            c=Mock();c.rect.return_value=(0,0,1000,800)
            im=Image.new('RGB',(200,300));region=dict(left=0,top=100,width=200,height=300)
            c.image.return_value=(im,region)
            s=Mock();s.c=c;s.menu=[0,.1,.2,.5];s.ocr.return_value=[];s.label_matches.return_value=False
            s.switch.side_effect=RuntimeError('找不到');s.numbers=None
            s.verify_label.return_value=False
            nav=Mock();nav.manual={};visited=[]
            def make_nav(*args,**kwargs):nav.show=kwargs['show'];return nav
            def locate(name,area):
                visited.append(nav.manual[name]);nav.show(dict(rows=[(1,50),(2,80)]));return (50,50)
            nav.locate.side_effect=locate
            def pump_until(predicate):
                deadline=time.monotonic()+3
                while time.monotonic()<deadline:
                    pending=scheduled[:];scheduled.clear()
                    for callback in pending:callback()
                    if predicate():return
                    time.sleep(.01)
                self.fail('Teaching UI did not respond')
            with patch.dict('sys.modules',{'MitakeDetector':types.SimpleNamespace(BASE=Path(d))}),patch('autoswitch.WindowsController',return_value=c),patch('autoswitch.RankingSwitcher',return_value=s),patch('autoswitch.RANKINGS',['漲停鎖住','跌停鎖住']),patch('menu_numbers.NumberNavigator',side_effect=make_nav),patch('ranking_training.replay_menu',return_value=(im,region)),patch('PIL.ImageTk.PhotoImage',return_value=object()):
                try:
                    open_training(app)
                    catalog=next(w for w in created if w.columns==('state','position'))
                    self.assertEqual(visited,[])
                    def choose(name):
                        catalog.selected=name;catalog.bindings['<ButtonRelease-1>'](types.SimpleNamespace(y=1))
                    choose('跌停鎖住')
                    pump_until(lambda:bool(visited))
                    # Process the image that enables the assistance controls.
                    pump_until(lambda:app.status.get().startswith('請在下方'))
                    buttons['標記上移一列']();pump_until(lambda:app.status.get().startswith('已移至第 1'))
                    buttons['標記下移一列']();pump_until(lambda:app.status.get().startswith('已移至第 2'))
                    buttons['標記上移一列']();pump_until(lambda:app.status.get().startswith('已移至第 1'))
                    c.click.assert_not_called()
                    buttons['確定學習']();pump_until(lambda:app.status.get().startswith('已記住「跌停鎖住」'))
                    choose('漲停鎖住');pump_until(lambda:app.status.get().startswith('請在下方截圖指出「漲停鎖住」'))
                    s.verify_label.assert_not_called();s.switch.assert_not_called()
                    buttons['確定學習']();pump_until(lambda:app.status.get().startswith('已記住「漲停鎖住」'))
                    buttons['停止']();pump_until(lambda:not app.worker_thread.is_alive())
                    c.click.assert_not_called()
                    import json
                    saved=json.loads((Path(d)/'ranking-teaching-positions.json').read_text())['items']
                    self.assertEqual(list(saved),['跌停鎖住','漲停鎖住'])
                    pump_until(lambda:app.status.get().startswith('已保存本次確認的 2 次'))
                    catalog=next(w for w in created if w.columns==('state','position'))
                    self.assertEqual(list(catalog.items),['漲停鎖住','跌停鎖住'])
                    self.assertTrue(all(item['values'][0]=='已記住待驗證' for item in catalog.items.values()))
                finally:
                    if hasattr(app,'stop_event'):app.stop_event.set()
                    if isinstance(app.worker_thread,threading.Thread):app.worker_thread.join(2)

@unittest.skipUnless(__import__('os').name=='nt','Windows Tk layout check')
class GuidedLayoutTests(unittest.TestCase):
    def test_primary_action_stays_visible_at_large_font_and_short_window(self):
        import tkinter as tk
        from tkinter import ttk
        from unittest.mock import Mock,patch
        from ranking_training import open_training
        root=tk.Tk();root.withdraw();root.tk.call('tk','scaling',2.0)
        app=Mock(root=root,tk=tk,ttk=ttk,generation=0)
        app.status=tk.StringVar(root)
        app.idle.return_value=True;app.dependencies.return_value=True
        app.config={'auto_config':{'combo':[0,0,.2,.1],'menu':[0,.1,.2,.5]}}
        c=Mock();c.rect.return_value=(0,0,1000,800)
        def walk(widget):
            for child in widget.winfo_children():
                yield child
                yield from walk(child)
        try:
            with patch('autoswitch.WindowsController',return_value=c),patch('ranking_training.threading.Thread'),patch.object(tk.Toplevel,'winfo_screenheight',return_value=720):
                open_training(app)
                top=next(w for w in root.winfo_children() if isinstance(w,tk.Toplevel))
                for height in (630,540):
                    top.geometry(f'680x{height}');root.update()
                    action=next(w for w in walk(top) if isinstance(w,ttk.Button) and w.cget('text')=='確定學習')
                    self.assertTrue(action.winfo_ismapped())
                    self.assertLessEqual(action.winfo_rooty()+action.winfo_height(),top.winfo_rooty()+top.winfo_height())
                    details=next(w for w in walk(top) if isinstance(w,ttk.Treeview))
                    self.assertFalse(details.winfo_ismapped())
                    from autoswitch import RANKINGS
                    catalog=next(w for w in walk(top) if isinstance(w,ttk.Treeview) and w.winfo_ismapped())
                    self.assertEqual(list(catalog.get_children()),RANKINGS)
                    catalog.see(RANKINGS[-1]);root.update()
                    self.assertTrue(catalog.bbox(RANKINGS[-1]))
        finally:
            if hasattr(app,'stop_event'):app.stop_event.set()
            root.destroy()

class LearnedCatalogTests(unittest.TestCase):
    def test_restores_manual_and_older_position_records(self):
        import tempfile
        from pathlib import Path
        from ranking_training import TeachingMemory,learned_positions
        from adaptive import Experience
        with tempfile.TemporaryDirectory() as d:
            memory=TeachingMemory(Path(d)/'teaching.json','layout')
            memory.remember('漲停鎖住',1,0,(50,20),(200,300))
            legacy=Experience();legacy.record_number('跌停鎖住',2)
            result=learned_positions(['漲停鎖住','跌停鎖住','即將漲停'],TeachingMemory(memory.path,'layout'),legacy)
            self.assertEqual(result,{'漲停鎖住':'第 1 項','跌停鎖住':'第 2 項'})
