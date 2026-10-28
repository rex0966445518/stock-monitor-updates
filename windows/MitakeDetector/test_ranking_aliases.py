import tempfile
import unittest
from pathlib import Path
from ranking_aliases import RankingAliases,RankingReader
from autoswitch import find_menu_item

class AliasTests(unittest.TestCase):
    def test_persistent_mapping_keeps_geometry_confidence_and_raw_observation(self):
        with tempfile.TemporaryDirectory() as d:
            a=RankingAliases(Path(d)/'aliases.json');a.save('買汽強大','買氣強大')
            a=RankingAliases(a.path);seen=[]
            box=[[0,0],[40,0],[40,20],[0,20]];tokens=[(box,'買汽強大',.9)]
            reader=RankingReader(lambda im:tokens,a,seen.append)
            corrected=reader(None)
            self.assertEqual(seen,[tokens]);self.assertEqual(corrected,[(box,'買氣強大',.9)])
            self.assertIsNotNone(find_menu_item(corrected,'買氣強大'))
            self.assertIsNone(find_menu_item(a.apply([(box,'買汽強大',.3)]),'買氣強大'))
            a.remove('買汽強大');self.assertEqual(RankingAliases(a.path).apply(tokens),tokens)
    def test_reject_other_rank_and_conflicting_mapping(self):
        with tempfile.TemporaryDirectory() as d:
            a=RankingAliases(Path(d)/'aliases.json')
            with self.assertRaises(ValueError):a.save('賣壓沉重','買氣強大')
            with self.assertRaises(ValueError):a.save('','買氣強大')
            a.save('買汽強大','買氣強大')
            with self.assertRaises(ValueError):a.save('買汽強大','連續買單')
    def test_corrupt_file_and_invalid_saved_target_are_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'aliases.json';path.write_text('[]')
            a=RankingAliases(path);self.assertEqual(a.mapping,{})
            a.mapping={'錯字':'不存在','賣壓沉重':'買氣強大'}
            tokens=[([], '錯字', .9),([], '賣壓沉重',.9)]
            self.assertEqual(a.apply(tokens),tokens)
