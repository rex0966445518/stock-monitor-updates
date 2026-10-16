import sqlite3
import tempfile
import unittest
from pathlib import Path
from version_history import record_launch,history_text


class HistoryTests(unittest.TestCase):
    def test_first_launch_is_preserved_and_release_notes_are_visible(self):
        with tempfile.TemporaryDirectory() as folder:
            record_launch(folder,'0.3.14')
            with sqlite3.connect(Path(folder)/'version-history.sqlite3') as db:first=db.execute('SELECT first_seen FROM launches').fetchone()[0]
            record_launch(folder,'0.3.14')
            with sqlite3.connect(Path(folder)/'version-history.sqlite3') as db:
                self.assertEqual(db.execute('SELECT first_seen FROM launches').fetchall(),[(first,)])
            text=history_text(folder)
            self.assertIn('0.3.13',text);self.assertIn('發布時間',text);self.assertIn('台北',text)


if __name__=='__main__':unittest.main()
