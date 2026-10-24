# HCI snoop analysis of the Bluetooth classic transfers (RFCOMM, credit based flow control): for each burst of data
# received by the phone, the rate, and the credits the phone gave back to the device. When the device has no credit
# left it must wait: time spent there is the phone (app, KnBle, stack) not taking the data fast enough.
#
#   python -I rfcomm.py btsnoop_hci.log.last btsnoop_hci.log [--tz=2]
#
# One "RX" line per burst, tab separated: start, bytes, ms, KB/s, credit grants, credits, credit-starved ms, longest
# starved ms, frames received.
import collections
import datetime
import struct
import sys

from snoop import records

BURST_GAP_S = 1.0
BURST_MIN_BYTES = 256 * 1024


def rfcomm_frames(payload):
    """(dlci, is_uih, pf, credits, data_len) of an RFCOMM frame, None when it is not one"""
    if len(payload) < 4:
        return None
    addr, ctrl = payload[0], payload[1]
    if not addr & 1:
        return None
    dlci = addr >> 2
    uih = (ctrl & 0xEF) == 0xEF
    pf = bool(ctrl & 0x10)
    if payload[2] & 1:
        length, off = payload[2] >> 1, 3
    else:
        length, off = (payload[2] >> 1) | (payload[3] << 7), 4
    credits = 0
    if uih and pf and dlci != 0:
        if off >= len(payload):
            return None
        credits = payload[off]
        off += 1
    return dlci, uih, pf, credits, length, payload[off:off + length]


def main():
    files = [a for a in sys.argv[1:] if not a.startswith('--')]
    tz = next((float(a.split('=')[1]) for a in sys.argv[1:] if a.startswith('--tz=')), 0)
    zone = datetime.timezone(datetime.timedelta(hours=tz))
    fmt = lambda t: datetime.datetime.fromtimestamp(t, zone).strftime('%H:%M:%S.%f')[:-3]
    recs = sorted((r for p in files for r in records(p)), key=lambda r: r[0])

    # L2CAP reassembly per (handle, direction)
    partial = {}
    events = []  # (t, sent by the phone, dlci, credits, data length)
    initial = []  # (t, sent by the phone, dlci, initial credits) from the PN negotiation
    for t, flags, d in recs:
        if d[0] != 0x02 or len(d) < 5:
            continue
        sent = (flags & 1) == 0
        hf, ln = struct.unpack('<HH', d[1:5])
        handle, pb = hf & 0xfff, (hf >> 12) & 3
        key = (handle, sent)
        data = d[5:5 + ln]
        if pb in (0, 2):
            if len(data) < 4:
                continue
            l2len, cid = struct.unpack('<HH', data[:4])
            partial[key] = [l2len, cid, bytearray(data[4:])]
        elif key in partial:
            partial[key][2] += data
        else:
            continue
        l2len, cid, buf = partial[key]
        if len(buf) < l2len:
            continue
        del partial[key]
        if cid < 0x0040:
            continue
        f = rfcomm_frames(bytes(buf))
        if f is None:
            continue
        dlci, uih, pf, credits, length, body = f
        if not uih:
            continue
        if dlci == 0:
            # Multiplexer control: PN gives the initial credits of a channel
            if len(body) >= 10 and (body[0] >> 2) == 0x20:
                initial.append((t, sent, body[2] & 0x3F, body[9] & 0x07))
            continue
        events.append((t, sent, dlci, credits, length))

    # Bursts of data received by the phone
    rx = [e for e in events if not e[1] and e[4] > 0]
    bursts, cur = [], []
    for e in rx:
        if cur and e[0] - cur[-1][0] > BURST_GAP_S:
            bursts.append(cur)
            cur = []
        cur.append(e)
    if cur:
        bursts.append(cur)

    print(f'PN negotiations (time, by the phone, DLCI, initial credits): {[(fmt(t), s, d, k) for t, s, d, k in initial[-6:]]}')
    for b in bursts:
        total = sum(e[4] for e in b)
        if total < BURST_MIN_BYTES:
            continue
        t0, t1, dlci = b[0][0], b[-1][0], b[0][2]
        # Credits of the device on this channel: those the phone gave before the burst are unknown, counted from the
        # balance at the start (the device had at least the frames it sent before the first grant)
        window = [e for e in events if t0 - 0.0005 <= e[0] <= t1 and e[2] == dlci]
        grants = [e for e in window if e[1] and e[3] > 0]
        balance, low = 0, 0
        timeline = []
        for t, sent, _, credits, length in window:
            if sent:
                balance += credits
            elif length > 0:
                balance -= 1
            low = min(low, balance)
            timeline.append((t, balance))
        # Shift so that the lowest point is 0: the device had exactly what it needed there
        starved, longest, since = 0.0, 0.0, None
        for t, bal in timeline:
            if bal - low <= 0:
                if since is None:
                    since = t
            elif since is not None:
                starved += t - since
                longest = max(longest, t - since)
                since = None
        ms = (t1 - t0) * 1000
        avg = sum(e[3] for e in grants) / len(grants) if grants else 0
        print(f'burst {fmt(t0)}: {total} bytes in {ms:.0f} ms = {total / 1024 / (ms / 1000):.1f} KB/s, {len(b)} frames; '
              f'{len(grants)} credit grants (avg {avg:.1f}), credit-starved {starved * 1000:.0f} ms (longest {longest * 1000:.0f} ms)')
        print(f'RX\t{t0:.3f}\t{total}\t{ms:.0f}\t{total / 1024 / (ms / 1000):.1f}\t{len(grants)}\t{sum(e[3] for e in grants)}\t{starved * 1000:.0f}'
              f'\t{longest * 1000:.0f}\t{len(b)}')


if __name__ == '__main__':
    main()
