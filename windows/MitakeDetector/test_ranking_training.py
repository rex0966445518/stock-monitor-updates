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
