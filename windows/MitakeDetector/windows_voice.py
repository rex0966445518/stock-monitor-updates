"""Queued Windows SAPI announcements using voices already installed on the PC."""
import base64
import queue
import subprocess
import threading


def speech_command(text):
    literal=str(text).replace("'","''")
    script="""$ErrorActionPreference='Stop';
$v=New-Object -ComObject SAPI.SpVoice;
foreach($t in $v.GetVoices()) { if(($t.GetAttribute('Language') -split ';') -contains '404') {$v.Voice=$t;break} };
$v.Rate=0;[void]$v.Speak('"""+literal+"');"
    encoded=base64.b64encode(script.encode('utf-16-le')).decode('ascii')
    return ['powershell.exe','-NoProfile','-NonInteractive','-EncodedCommand',encoded]


class WindowsVoice:
    def __init__(self,on_error):
        self.messages=queue.Queue(maxsize=8);self.closed=False;self.process=None;self.on_error=on_error
        self.lock=threading.Lock()
        threading.Thread(target=self.run,daemon=True).start()

    def say(self,message):
        if self.closed:return
        try:self.messages.put_nowait(str(message))
        except queue.Full:pass

    def run(self):
        while True:
            message=self.messages.get()
            if message is None or self.closed:return
            try:
                with self.lock:
                    if self.closed:return
                    self.process=subprocess.Popen(speech_command(message),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,creationflags=0x08000000)
                    process=self.process
                try:code=process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill();process.wait();raise RuntimeError('語音引擎逾時')
                if code:raise RuntimeError('Windows語音引擎無法播放，請確認已安裝中文語音及音訊輸出')
            except Exception as exc:
                if not self.closed:self.on_error(str(exc))
            finally:
                with self.lock:self.process=None

    def close(self):
        with self.lock:
            self.closed=True
            if self.process is not None and self.process.poll() is None:
                try:self.process.terminate()
                except OSError:pass
        try:self.messages.put_nowait(None)
        except queue.Full:pass
