"""Keeps call stream tokens out of the logs of the engine and the gateway.

Plivo opens each call's stream at /call?call_id=...&token=..., and uvicorn writes that address into its log as the
stream opens ("WebSocket /call?..." [accepted]) and in its access log. The token is what lets a stream into the call,
so it must not sit in log files that are kept, copied or shared. This filter writes token=[hidden] instead; the call
id stays, so a call can still be found in the log.
"""

import logging
import re

TOKEN = re.compile(r"(token=)[^&\s\"']+")
LOGGERS = ("uvicorn.error", "uvicorn.access", "phone")


def hide(text: str) -> str:
    return TOKEN.sub(r"\1[hidden]", text)


class HideTokens(logging.Filter):
    """Edits the message and each of its arguments in place, so formatters that unpack the arguments (uvicorn's access
    log does) still get the same number of them."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = hide(record.msg)
        if isinstance(record.args, tuple):
            record.args = tuple(hide(a) if isinstance(a, str) else a for a in record.args)
        elif isinstance(record.args, dict):
            record.args = {k: hide(v) if isinstance(v, str) else v for k, v in record.args.items()}
        return True


def install() -> None:
    """On uvicorn's loggers, once (importing a module twice does not add a second filter)."""
    for name in LOGGERS:
        logger = logging.getLogger(name)
        if not any(isinstance(f, HideTokens) for f in logger.filters):
            logger.addFilter(HideTokens())
