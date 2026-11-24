"""Call stream tokens never reach the engine's or the gateway's log (redact.py).

    .venv\\Scripts\\python -m unittest test_redact
"""

import io
import logging
import socket
import threading
import time
import unittest

import uvicorn
from uvicorn.logging import AccessFormatter, DefaultFormatter

import engine
import gateway
import phone
import redact

TOKEN = "499ef205619f024c888bfcfd426475bdf32a9175e9d88b711887341de77d54ad"


class Captured:
    """A handler on one of uvicorn's loggers, with uvicorn's own formatter, at INFO for the test."""

    def __init__(self, name, formatter):
        self.logger, self.out = logging.getLogger(name), io.StringIO()
        self.handler = logging.StreamHandler(self.out)
        self.handler.setFormatter(formatter)

    def __enter__(self):
        self.level = self.logger.level
        self.logger.setLevel(logging.INFO)
        self.logger.addHandler(self.handler)
        return self

    def __exit__(self, *exc):
        self.logger.removeHandler(self.handler)
        self.logger.setLevel(self.level)

    def text(self):
        return self.out.getvalue()


class HideTest(unittest.TestCase):

    def test_the_token_is_hidden_and_the_call_id_kept(self):
        self.assertEqual(redact.hide(f"/call?call_id=c1&token={TOKEN}"), "/call?call_id=c1&token=[hidden]")
        self.assertEqual(redact.hide(f"/call?token={TOKEN}&call_id=c1 x"), "/call?token=[hidden]&call_id=c1 x")
        self.assertEqual(redact.hide("/health"), "/health")

    def test_both_apps_install_it_once_on_both_loggers(self):
        redact.install()
        for name in redact.LOGGERS:
            self.assertEqual(sum(isinstance(f, redact.HideTokens) for f in logging.getLogger(name).filters), 1, name)
        self.assertIs(engine.redact, redact)
        self.assertIs(gateway.redact, redact)

    def test_uvicorns_access_log_still_formats(self):
        with Captured("uvicorn.access", AccessFormatter("%(client_addr)s - \"%(request_line)s\" %(status_code)s",
                                                         use_colors=False)) as log:
            logging.getLogger("uvicorn.access").info('%s - "%s %s HTTP/%s" %d', "127.0.0.1:5000", "GET",
                                                     f"/call?call_id=c1&token={TOKEN}", "1.1", 101)
        self.assertIn('"GET /call?call_id=c1&token=[hidden] HTTP/1.1" 101', log.text())
        self.assertNotIn(TOKEN, log.text())


class CallLinesTest(unittest.TestCase):
    """The calls' own lines (phone.py) reach uvicorn's console at INFO, once, and without a token."""

    def test_the_calls_lines_reach_uvicorns_console(self):
        uvicorn_log = logging.getLogger("uvicorn")
        saved = (uvicorn_log.handlers[:], phone.log.handlers[:], phone.log.level, phone.log.propagate)

        def restore():
            uvicorn_log.handlers[:], phone.log.handlers[:] = saved[0], saved[1]
            phone.log.setLevel(saved[2])
            phone.log.propagate = saved[3]
        self.addCleanup(restore)
        out = io.StringIO()
        console = logging.StreamHandler(out)
        console.setFormatter(DefaultFormatter("%(levelprefix)s %(message)s", use_colors=False))
        uvicorn_log.handlers[:] = [console]         # as uvicorn's own set-up leaves it, before it imports the engine
        phone.log.handlers[:] = []
        phone.log.setLevel(logging.NOTSET)
        phone.log.propagate = True
        engine.show_call_lines()
        engine.show_call_lines()                    # twice: still printed once
        phone.log.info("call c1: reply 1 sent 1.20 s after the caller stopped")
        phone.log.warning("call c1: could not send the recording: %s", f"/call?call_id=c1&token={TOKEN}")
        self.assertEqual(out.getvalue().count("reply 1 sent"), 1)
        self.assertIn("INFO:     call c1: reply 1 sent 1.20 s after the caller stopped", out.getvalue())
        self.assertIn("WARNING:  call c1: could not send the recording: /call?call_id=c1&token=[hidden]",
                      out.getvalue())
        self.assertNotIn(TOKEN, out.getvalue())


class ServerTest(unittest.TestCase):
    """The real engine under a real uvicorn: the line uvicorn writes when a stream is refused carries the address."""

    def test_a_refused_stream_is_logged_without_its_token(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        saved = (engine.WARM_UP, engine.VOICE_HMAC_SECRET)
        engine.WARM_UP, engine.VOICE_HMAC_SECRET = "off", "s" * 40
        # uvicorn sets up its loggers here, replacing their handlers (not the filter): the capture comes after.
        server = uvicorn.Server(uvicorn.Config(engine.app, host="127.0.0.1", port=port, log_level="info"))
        with Captured("uvicorn.error", DefaultFormatter("%(message)s", use_colors=False)) as log:
            threading.Thread(target=server.run, daemon=True).start()
            try:
                for _ in range(100):
                    if server.started:
                        break
                    time.sleep(0.05)
                import websockets.sync.client as ws
                wrong = phone.stream_token("another secret", "c1")
                with self.assertRaises(Exception):
                    with ws.connect(f"ws://127.0.0.1:{port}/call?call_id=c1&token={wrong}") as c:
                        c.recv(timeout=5)
                for _ in range(50):
                    if "WebSocket" in log.text():
                        break
                    time.sleep(0.05)
            finally:
                server.should_exit = True
                engine.WARM_UP, engine.VOICE_HMAC_SECRET = saved
        self.assertIn("/call?call_id=c1&token=[hidden]", log.text())
        self.assertNotIn(wrong, log.text())


if __name__ == "__main__":
    unittest.main()
