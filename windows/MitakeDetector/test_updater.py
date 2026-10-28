import copy
import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from updater import check_update, download_installer, validate_manifest, version_tuple, REPO

class UpdateTests(unittest.TestCase):
    def test_download_reports_actual_bytes_and_verified_stages(self):
        with tempfile.TemporaryDirectory() as folder:
            samples=[];stages=[]
            download_installer(self.manifest,folder,opener=lambda *a,**k:io.BytesIO(self.payload),
                               transfer=lambda *values:samples.append(values),stage=stages.append)
            self.assertEqual(samples[0],(0,len(self.payload),0))
            self.assertEqual(samples[-1][:2],(len(self.payload),len(self.payload)))
            self.assertGreaterEqual(samples[-1][2],0)
            self.assertEqual(stages,['connecting','verifying','ready'])

    def test_failed_hash_never_reports_ready(self):
        with tempfile.TemporaryDirectory() as folder:
            stages=[];progress=[]
            with self.assertRaises(ValueError):
                download_installer(self.manifest,folder,progress.append,lambda *a,**k:io.BytesIO(b'XX'+self.payload[2:]),stage=stages.append)
            self.assertNotIn('ready',stages);self.assertNotIn(100,progress)

    def test_restart_environment_removes_stale_parent_state(self):
        from updater import installer_environment
        original={'_PYI_APPLICATION_HOME_DIR':'missing-MEI','_PYI_ARCHIVE_FILE':'old.exe','_PYI_PARENT_PROCESS_LEVEL':'1','_MEIPASS2':'old','TEMP':'user-temp','CUSTOM':'keep'}
        clean=installer_environment(original)
        self.assertEqual(clean,{'TEMP':'user-temp','CUSTOM':'keep','PYINSTALLER_RESET_ENVIRONMENT':'1'})
        self.assertIn('_PYI_APPLICATION_HOME_DIR',original)

    def test_installer_launch_uses_independent_environment(self):
        from unittest.mock import patch
        from updater import launch_installer
        with patch('updater.subprocess.Popen') as popen:
            launch_installer(Path('verified-setup.exe'))
            args,kwargs=popen.call_args
            self.assertEqual(args[0][0],'verified-setup.exe')
            self.assertIn('/LAUNCHAPP=1',args[0])
            self.assertEqual(kwargs['env']['PYINSTALLER_RESET_ENVIRONMENT'],'1')
            self.assertTrue(kwargs['close_fds'])
    def setUp(self):
        self.payload=b'MZ'+b'installer-test'*200
        self.manifest=dict(product='MitakeDetector',platform='windows-x64',channel='stable',version='0.2.0',size=len(self.payload),sha256=hashlib.sha256(self.payload).hexdigest(),url=f'https://github.com/{REPO}/releases/download/mitake-v0.2.0/MitakeDetector-Setup-v0.2.0.exe')
    def test_version_order(self):
        self.assertGreater(version_tuple('0.10.0'),version_tuple('0.9.9'))
        for value in ['v0.2.0','0.2','0.2.0.exe',None]:
            with self.assertRaises(ValueError):version_tuple(value)
    def test_wrong_product_and_url_rejected(self):
        for key,value in [('product','StockMonitor'),('platform','android'),('channel','beta'),('url','https://example.com/setup.exe'),('size',True),('sha256','')]:
            data=dict(self.manifest);data[key]=value
            with self.assertRaises(ValueError):validate_manifest(data)
    def test_latest_and_older(self):
        import json
        opener=lambda *a,**k:io.BytesIO(json.dumps(self.manifest).encode())
        self.assertIsNotNone(check_update('0.1.0',opener))
        self.assertIsNone(check_update('0.2.0',opener))
        self.assertIsNone(check_update('1.0.0',opener))
    def test_valid_download(self):
        with tempfile.TemporaryDirectory() as folder:
            seen=[]
            path=download_installer(self.manifest,folder,seen.append,lambda *a,**k:io.BytesIO(self.payload))
            self.assertEqual(path.read_bytes(),self.payload)
            self.assertEqual(seen[-1],100)
            self.assertFalse(list(Path(folder).glob('*.partial')))
    def test_corruption_truncation_and_overflow_never_install(self):
        for payload in [self.payload[:-1],self.payload+b'x',b'XX'+self.payload[2:]]:
            with tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):download_installer(self.manifest,folder,opener=lambda *a,**k:io.BytesIO(payload))
                self.assertFalse(list(Path(folder).iterdir()))
    def test_network_failure_keeps_existing_file(self):
        def failed(*a,**k):raise OSError('offline')
        with tempfile.TemporaryDirectory() as folder:
            old=Path(folder)/'MitakeDetector-Setup-v0.2.0.exe';old.write_bytes(b'old')
            with self.assertRaises(OSError):download_installer(self.manifest,folder,opener=failed)
            self.assertEqual(old.read_bytes(),b'old')
if __name__=='__main__':unittest.main()

class RangedDownloadTests(unittest.TestCase):
    def setUp(self):
        self.payload=b'MZ'+bytes(range(256))*8192
        self.manifest=dict(product='MitakeDetector',platform='windows-x64',channel='stable',version='0.3.41',size=len(self.payload),sha256=hashlib.sha256(self.payload).hexdigest(),url=f'https://github.com/{REPO}/releases/download/mitake-v0.3.41/MitakeDetector-Setup-v0.3.41.exe')
    def response(self,body,status=200,headers=None):
        out=io.BytesIO(body);out.status=status;out.headers=headers or {};return out
    def opener(self,req,**kwargs):
        import re
        value=req.get_header('Range')
        if not value:return self.response(self.payload)
        start,end=map(int,re.fullmatch(r'bytes=(\d+)-(\d+)',value).groups())
        return self.response(self.payload[start:end+1],206,{'Content-Range':f'bytes {start}-{end}/{len(self.payload)}'})
    def test_parallel_ranges_verify_complete_installer(self):
        import threading,time
        threads=set()
        def opener(req,**kw):
            if req.get_header('Range')!='bytes=0-0':threads.add(threading.current_thread().name);time.sleep(.01)
            return self.opener(req,**kw)
        with tempfile.TemporaryDirectory() as folder:
            p=download_installer(self.manifest,folder,opener=opener)
            self.assertEqual(p.read_bytes(),self.payload)
            self.assertGreater(len(threads),1)
            self.assertEqual(list(Path(folder).iterdir()),[p])
    def test_server_without_range_falls_back_to_single(self):
        requests=[]
        def opener(req,**kw):requests.append(req.get_header('Range'));return self.response(self.payload)
        with tempfile.TemporaryDirectory() as folder:
            p=download_installer(self.manifest,folder,opener=opener)
            self.assertEqual(p.read_bytes(),self.payload)
        self.assertEqual(requests,['bytes=0-0',None])
    def test_network_failure_resumes_segments(self):
        fail=[True]
        def opener(req,**kw):
            response=self.opener(req,**kw);value=req.get_header('Range')
            if fail[0] and value and value.startswith('bytes=') and value!='bytes=0-0' and int(value.split('=')[1].split('-')[0])<len(self.payload)//4:
                original=response.read;reads=[0]
                def read(size=-1):
                    reads[0]+=1
                    if reads[0]>1:raise OSError('temporary network failure')
                    return original(size)
                response.read=read
            return response
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(OSError):download_installer(self.manifest,folder,opener=opener)
            self.assertTrue(list(Path(folder).glob('*.segment*')))
            fail[0]=False;samples=[]
            p=download_installer(self.manifest,folder,opener=opener,transfer=lambda *a:samples.append(a))
            self.assertGreater(samples[0][0],0)
            self.assertEqual(p.read_bytes(),self.payload)
    def test_corrupt_parallel_result_is_never_installed(self):
        good=self.manifest['sha256'];self.payload=self.payload[:-1]+b'x'
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):download_installer(self.manifest,folder,opener=self.opener)
            self.assertFalse(list(Path(folder).iterdir()))
    def test_valid_local_installer_is_reused(self):
        with tempfile.TemporaryDirectory() as folder:
            p=download_installer(self.manifest,folder,opener=self.opener)
            def offline(*a,**k):raise AssertionError('Should reuse verified local installer')
            self.assertEqual(download_installer(self.manifest,folder,opener=offline),p)

class SingleResumeTests(unittest.TestCase):
    setUp=UpdateTests.setUp
    def test_single_resume_appends_only_matching_range(self):
        with tempfile.TemporaryDirectory() as folder:
            partial=Path(folder)/'MitakeDetector-Setup-v0.2.0.exe.partial'
            partial.write_bytes(self.payload[:100])
            def opener(req,**kw):
                self.assertEqual(req.get_header('Range'),f'bytes=100-{len(self.payload)-1}')
                response=io.BytesIO(self.payload[100:]);response.status=206
                response.headers={'Content-Range':f'bytes 100-{len(self.payload)-1}/{len(self.payload)}'}
                return response
            final=download_installer(self.manifest,folder,opener=opener)
            self.assertEqual(final.read_bytes(),self.payload)
    def test_single_ignored_range_restarts_instead_of_appending(self):
        with tempfile.TemporaryDirectory() as folder:
            partial=Path(folder)/'MitakeDetector-Setup-v0.2.0.exe.partial'
            partial.write_bytes(self.payload[:100])
            def opener(req,**kw):
                response=io.BytesIO(self.payload);response.status=200;return response
            final=download_installer(self.manifest,folder,opener=opener)
            self.assertEqual(final.read_bytes(),self.payload)
