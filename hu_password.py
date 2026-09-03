#!/usr/bin/env python3
"""
e.MAS 5 / IHU801P — rolling 6-digit engineer password generator.

The About-IHU dialog (Settings -> My Vehicle -> About IHU, long-press the
software version) accepts the code computed as:

    md5( yyyyMMddHHmm(5-min slot, GMT+8) + IHUID + "universal168" )
      -> hex, upper, -> BigInteger, -> decimal string
      -> reverse digits, take every 2nd digit starting at 0
      -> first 6 chars

Usage:
    python hu_password.py "021600000000000000000000"            # now
    python hu_password.py "021600000000000000000000" 2026-09-03T12:55+08:00
"""
import hashlib
import sys
from datetime import datetime, timedelta, timezone

GMT8 = timezone(timedelta(hours=8))


def slot_now() -> str:
    now = datetime.now(GMT8)
    return snap_minute(now.strftime("%Y%m%d%H%M"))


def snap_minute(s: str) -> str:
    tail = int(s[-1])
    tail = 0 if tail < 5 else 5
    return s[:-1] + str(tail)


def code_for(ihuid: str, when: datetime | None = None, salt: str = "universal168") -> str:
    date = slot_now() if when is None else snap_minute(when.astimezone(GMT8).strftime("%Y%m%d%H%M"))
    digest = hashlib.md5((date + ihuid + salt).encode()).hexdigest()
    big = str(int(digest.upper(), 16))
    rev = big[::-1]
    odd = rev[::2]
    return odd[:6]


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    ihuid = sys.argv[1]
    when = datetime.fromisoformat(sys.argv[2]) if len(sys.argv) > 2 else None
    print(code_for(ihuid, when))
