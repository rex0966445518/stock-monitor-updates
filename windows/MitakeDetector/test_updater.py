import copy
import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from updater import check_update, download_installer, validate_manifest, version_tuple, REPO

class UpdateTests(unittest.TestCase):
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
