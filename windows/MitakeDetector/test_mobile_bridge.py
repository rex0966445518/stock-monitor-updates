import copy
import json
import tempfile
import time
import unittest
import urllib.request
import urllib.error
from pathlib import Path
from MitakeDetector import DEFAULT,Quote
from mobile_bridge import MobileBridge

class BridgeTests(unittest.TestCase):
    def cfg(self):return dict(DEFAULT,use_turnover=True,min_turnover=2,use_accel=True,accel=1.5,use_breakout=True,cooldown=300)
    def row(self,stamp,ratio=2,hit=False,reason='等待觀測突破守穩',turnover=4):return (Quote('2330','台積電',100,3,2000,turnover,stamp),ratio,reason,hit)
    def test_upgrade_dedup_weak_stale_and_paused(self):
        with tempfile.TemporaryDirectory() as folder:
            b=MobileBridge(Path(folder)/'pair.json');now=time.time()-10;cfg=self.cfg()
            b.publish([self.row(now)],cfg,'test',now)
            self.assertEqual((b.seq,b.snapshot()['rows'][0]['matched']),(1,5))
            b.publish([self.row(now)],cfg,'test',now+1);self.assertEqual(b.seq,1)
            b.publish([self.row(now+2,hit=True,reason='轉強候選')],cfg,'test',now+2)
            self.assertEqual(b.seq,2)
            self.assertTrue(b.snapshot()['events'][-1]['confirmed'])
            b.publish([self.row(now+3,ratio=None,turnover=0)],cfg,'test',now+3)
            self.assertEqual(b.seq,2);self.assertEqual(b.snapshot()['rows'][0]['peak'],6)
            b.publish([self.row(now-100,hit=True,reason='轉強候選')],cfg,'test',now+4);self.assertEqual(b.seq,2)
            self.assertFalse(b.snapshot()['active'])
    def test_invalid_and_disabled_conditions_do_not_emit(self):
        with tempfile.TemporaryDirectory() as folder:
            b=MobileBridge(Path(folder)/'pair.json');now=time.time()-10
            b.publish([self.row(now,hit=True,reason='掃描讀值已過期')],self.cfg(),'test',now)
            b.publish([self.row(now+1,hit=True,reason='轉強候選')],dict(self.cfg(),use_accel=False),'test',now+1)
            self.assertEqual(b.seq,0)
    def test_local_http_requires_pairing_and_supports_cursor_rotation(self):
        with tempfile.TemporaryDirectory() as folder:
            b=MobileBridge(Path(folder)/'pair.json',port=0);b.start('127.0.0.1')
            try:
                url=f'http://127.0.0.1:{b.port}/v1/snapshot'
                with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(url)
                self.assertEqual(error.exception.code,401)
                def get(path=''):
                    return json.load(urllib.request.urlopen(urllib.request.Request(url+path,headers={'Authorization':'Bearer '+b.token})))
                now=time.time()-10;b.publish([self.row(now)],self.cfg(),'test',now)
                self.assertEqual(len(get()['events']),1)
                self.assertEqual(get(f'?after=1&session={b.session}')['events'],[])
                old=b.token;b.rotate()
                with self.assertRaises(urllib.error.HTTPError):urllib.request.urlopen(urllib.request.Request(url,headers={'Authorization':'Bearer '+old}))
                self.assertEqual(MobileBridge(b.path).token,b.token)
            finally:b.close()
    def test_restart_changes_session_and_day_rolls_history(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'pair.json';b=MobileBridge(path);now=time.time()-10
            b.publish([self.row(now)],self.cfg(),'test',now)
            b.publish([self.row(now+86400)],self.cfg(),'test',now+86400)
            self.assertEqual(len(b.rows),1);self.assertEqual(len(b.events),1)
            self.assertNotEqual(MobileBridge(path).session,b.session)
