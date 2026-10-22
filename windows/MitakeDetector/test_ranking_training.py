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
