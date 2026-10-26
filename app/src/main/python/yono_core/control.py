"""Cooperative cancellation, deadlines and bounded, machine-readable events."""
from __future__ import annotations
import json
import threading
import time
from .model import EngineError


class Stopped(BaseException):
    """Not Exception: third-party `except Exception` must not swallow cancellation."""
    def __init__(self, code="CANCELLED"):
        self.code = code


class ItemCancelled(Stopped):
    pass


class Control:
    def __init__(self, job_id, callback=None, timeout=900, clock=time.monotonic):
        self.job_id, self.callback, self.clock = job_id, callback, clock
        self.deadline = clock() + min(7200, max(5, float(timeout)))
        self.cancel_event = threading.Event()
        self.sequence = 0
        self._last_transfer = 0
        self.current_entry = ""

    def cancel(self):
        self.cancel_event.set()

    def check(self):
        if self.callback and hasattr(self.callback, "isCancelled") and self.callback.isCancelled():
            self.cancel_event.set()
        if self.cancel_event.is_set():
            raise Stopped()
        if self.current_entry and self.callback and hasattr(self.callback, "isEntryCancelled") and self.callback.isEntryCancelled(self.current_entry):
            raise ItemCancelled("ITEM_CANCELLED")
        if self.clock() >= self.deadline:
            raise Stopped("TIMEOUT")

    def remaining(self):
        self.check()
        return max(.1, self.deadline - self.clock())

    def emit(self, stage, **values):
        self.sequence += 1
        event = {"schema": 1, "job_id": self.job_id, "sequence": self.sequence,
                 "stage": stage, **values}
        if self.callback:
            payload = json.dumps(event, ensure_ascii=False, allow_nan=False)
            try:
                if hasattr(self.callback, "onEvent"):
                    self.callback.onEvent(payload)
                else:
                    self.callback(payload)
            except Exception as error:
                raise EngineError("CALLBACK_FAILED", "Jembatan progres Android gagal.") from error

    def hook(self, data):
        self.check()
        now = self.clock()
        if now - self._last_transfer < .25 and data.get("status") != "finished":
            return
        self._last_transfer = now
        self.emit("downloading", downloaded_bytes=data.get("downloaded_bytes", 0),
                  total_bytes=data.get("total_bytes") or data.get("total_bytes_estimate"),
                  speed=data.get("speed"), eta=data.get("eta"))

    def wait(self, duration):
        end = self.clock() + min(float(duration), self.remaining())
        while self.clock() < end:
            self.check()
            self.cancel_event.wait(min(.1, max(0, end - self.clock())))
        self.check()
