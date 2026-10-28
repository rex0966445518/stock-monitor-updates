import unittest
from unittest.mock import patch, Mock
from windows_voice import WindowsVoice


class VoiceTests(unittest.TestCase):
    def voice(self):
        with patch('windows_voice.threading.Thread'):
            return WindowsVoice(Mock())

    def test_candidate_survives_full_routine_queue_and_goes_first(self):
        voice=self.voice()
        for n in range(8):self.assertTrue(voice.say(str(n)))
        self.assertFalse(voice.say('overflow'))
        self.assertTrue(voice.say('5/6',priority=True))
        self.assertTrue(voice.say('6/6',priority=True))
        self.assertEqual(voice.messages.get()[2],'5/6')
        self.assertEqual(voice.messages.get()[2],'6/6')
        self.assertEqual(voice.messages.get()[2],'0')

    def test_closed_voice_does_not_accept_alerts(self):
        voice=self.voice();voice.close()
        self.assertFalse(voice.say('5/6',priority=True))

    def test_engine_failure_is_reported_and_worker_continues(self):
        voice=self.voice();voice.say('5/6',priority=True)
        voice.messages.put((2,999,None))
        with patch('windows_voice.subprocess.Popen',side_effect=OSError('missing engine')):
            voice.run()
        voice.on_error.assert_called_once_with('missing engine')

    def test_long_alert_is_allowed_sixty_seconds(self):
        voice=self.voice();voice.say('5/6',priority=True)
        voice.messages.put((2,999,None))
        process=Mock();process.wait.return_value=0
        with patch('windows_voice.subprocess.Popen',return_value=process):voice.run()
        process.wait.assert_called_once_with(timeout=60)
        voice.on_error.assert_not_called()
