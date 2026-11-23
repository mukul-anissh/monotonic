"""Monotonic clock utilities.

Never uses wall clock time; relies solely on ``time.monotonic_ns()``.
"""

from collections.abc import Callable
import time

ClockFn = Callable[[], int]


def default_clock() -> int:
    """Return the authoritative monotonic server timestamp in nanoseconds."""
    return time.monotonic_ns()
