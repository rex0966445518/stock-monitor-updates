import datetime as dt
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from market_index import parse,presentation,MarketIndex,TAIPEI

class MarketIndexTests(unittest.TestCase):
    def sample(self):
        return {'rtcode':'0000','msgArray':[dict(c='t00',ex='tse',z='46771.67',y='46822.08',d='20261020',t='10:02:00')]}
    def test_official_index_identity_number_and_source_timestamp(self):
        quote=parse(self.sample());self.assertAlmostEqual(quote['change'],-50.41)
        self.assertEqual(dt.datetime.fromtimestamp(quote['ts'],TAIPEI).strftime('%H:%M:%S'),'10:02:00')
        data=self.sample();data['msgArray'][0]['c']='2330'
        with self.assertRaises(ValueError):parse(data)
        data=self.sample();data['msgArray'][0]['z']='-'
        with self.assertRaises(ValueError):parse(data)
    def test_stale_error_and_missing_quotes_never_claim_current_data(self):
        q=parse(self.sample())
        self.assertIn('最新來源行情',presentation(q,now=q['ts']+10)[0])
        self.assertIn('非即時',presentation(q,now=q['ts']+91)[0])
        self.assertIn('保留前值',presentation(q,error='offline',now=q['ts']+10)[0])
        self.assertNotIn('46,771',presentation(None,error='offline')[0])
    def test_network_failure_is_reported_without_retry_loop_or_tk_calls(self):
        events=[]
        def fail():raise OSError('offline')
        service=MarketIndex(lambda payload:(events.append(payload),service.close()),fetcher=fail)
        service.start();service.thread.join(1)
        self.assertFalse(service.thread.is_alive());self.assertEqual(events,[{'error':'offline'}])
    def test_out_of_order_index_does_not_replace_newer_quote(self):
        from dashboard_ui import DashboardUI
        old=parse(self.sample());new=dict(old,ts=old['ts']+15,price=47000)
        app=SimpleNamespace(index_quote=new,index_error='',refresh_index_label=lambda:None)
        DashboardUI.update_market_index(app,dict(quote=old,error=''))
        self.assertEqual(app.index_quote['price'],47000)
    def test_today_panel_keeps_weak_stock_and_uses_last_valid_price(self):
        from dashboard_ui import DashboardUI
        class Tree:
            def __init__(self):self.rows={}
            def selection(self):return []
            def get_children(self):return list(self.rows)
            def delete(self,*keys):self.rows={}
            def insert(self,*args,**kwargs):self.rows['row']=kwargs;return 'row'
        class Frame:
            def configure(self,**kwargs):self.text=kwargs['text']
        class Store:
            def stocks(self,day):return [('2330','TEST',10000,100,10010,20,4,6,'已轉弱',5,3)]
            def latest_flags(self,day):return {'2330':(10020,1,'量倒退',6,6)}
            def qualification_counts(self,day):return {'2330':2}
        tree=Tree();app=SimpleNamespace(today_tree=tree,today_frame=Frame(),diary_store=Store(),daily_refresh=0)
        DashboardUI.refresh_today_candidates(app,force=True)
        values=tree.rows['row']['values'];self.assertEqual(values[4],'20.00');self.assertIn('待複查',values[5])
        self.assertEqual(values[0],'2330');self.assertEqual(values[6],2)
