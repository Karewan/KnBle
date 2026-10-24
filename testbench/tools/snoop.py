# HCI snoop analysis of the bench transfers: whether the host (app + KnBle + Android stack) or the radio limits a
# transfer. Reads btsnoop files (H4, "full" mode), prints for each LE connection the link setup (create and update
# commands, PHY, data length, MTU exchanges, L2CAP channels, scan and advertising state) and for each burst of ATT Write
# Commands (a splittedWrite or an OTA) how full the controller stays and how fast the host refills it.
#
#   python -I snoop.py btsnoop_hci.log.last btsnoop_hci.log [--tz=2]
#
# --tz: hours added to the timestamps for display (the Pixel logs in UTC, Samsung in local time).
# One "BURST" line per burst, tab separated, for join.py.
import collections
import datetime
import struct
import sys

EPOCH_DELTA = 0x00dcddb30f2f8000
# Write Commands closer than this belong to the same burst
BURST_GAP_S = 1.0
BURST_MIN_WRITES = 100
# No packet completed for this long while some wait in the controller: the radio stalled
STALL_S = 0.020


def records(path):
    with open(path, 'rb') as f:
        if f.read(16)[:8] != b'btsnoop\x00':
            raise SystemExit(f'{path}: not a btsnoop file')
        while True:
            h = f.read(24)
            if len(h) < 24:
                return
            orig, incl, flags, drops, ts = struct.unpack('>IIIIq', h)
            data = f.read(incl)
            if len(data) < incl:
                return
            yield (ts - EPOCH_DELTA) / 1e6, flags, data


class Conn:
    def __init__(self, handle, t, addr):
        self.handle, self.t0, self.t1, self.addr = handle, t, None, addr
        self.events = []
        # Timeline of the ACL packets in the controller: (t, +1 sent by the host or -n completed, write command?)
        self.flow = []
        self.writes = []  # (t, attribute handle) of the ATT Write Commands
        self.mtu_requests = 0
        self.att_ops = collections.Counter()
        self.cids = collections.Counter()


def us(v, unit):
    return f'{v * unit:.2f}'


def parse(recs):
    conns, closed, radio = {}, [], []
    pending_create = None
    acl_buffers = None
    for t, flags, d in recs:
        typ, sent = d[0], (flags & 1) == 0
        if typ == 0x01 and len(d) >= 4:
            op, p = d[1] | d[2] << 8, d[4:]
            if op == 0x2043 and len(p) > 9:  # LE Extended Create Connection
                params, off = [], 10
                for _ in range(bin(p[9]).count('1')):
                    s = p[off:off + 16]
                    if len(s) < 16:
                        break
                    si, sw, imin, imax, lat, to, cemin, cemax = struct.unpack('<8H', s)
                    params.append(f'scan {us(si, .625)}/{us(sw, .625)} ms interval {us(imin, 1.25)}-{us(imax, 1.25)} ms latency {lat} '
                                  f'timeout {to * 10} ms CE {us(cemin, .625)}-{us(cemax, .625)} ms')
                    off += 16
                pending_create = f'LE Extended Create Connection: initiating PHYs 0x{p[9]:02x}, ' + ' | '.join(params)
            elif op == 0x200D and len(p) >= 25:  # LE Create Connection
                imin, imax, lat, to, cemin, cemax = struct.unpack('<6H', p[13:25])
                pending_create = (f'LE Create Connection: interval {us(imin, 1.25)}-{us(imax, 1.25)} ms latency {lat} timeout {to * 10} ms '
                                  f'CE {us(cemin, .625)}-{us(cemax, .625)} ms')
            elif op == 0x2013 and len(p) >= 14:  # LE Connection Update
                h, imin, imax, lat, to, cemin, cemax = struct.unpack('<7H', p[:14])
                if (c := conns.get(h & 0xfff)):
                    c.events.append((t, f'host asks connection update: interval {us(imin, 1.25)}-{us(imax, 1.25)} ms latency {lat} '
                                        f'timeout {to * 10} ms CE {us(cemin, .625)}-{us(cemax, .625)} ms'))
            elif op == 0x2032 and len(p) >= 5:  # LE Set PHY
                if (c := conns.get((p[0] | p[1] << 8) & 0xfff)):
                    c.events.append((t, f'host asks PHY: all 0x{p[2]:02x} tx 0x{p[3]:02x} rx 0x{p[4]:02x}'))
            elif op == 0x2022 and len(p) >= 6:  # LE Set Data Length
                h, tx, txt = struct.unpack('<3H', p[:6])
                if (c := conns.get(h & 0xfff)):
                    c.events.append((t, f'host asks data length {tx} B / {txt} us'))
            elif op in (0x200C, 0x2042) and p:
                radio.append((t, 'scan', p[0]))
            elif op in (0x200A, 0x2039) and p:
                radio.append((t, 'advertising', p[0]))
        elif typ == 0x04 and len(d) >= 4:
            code, p = d[1], d[3:]
            if code == 0x3E:
                sub = p[0]
                if sub in (0x01, 0x0A, 0x29) and len(p) >= 18:  # connection complete
                    status, h = p[1], p[2] | p[3] << 8
                    addr = ':'.join(f'{b:02X}' for b in reversed(p[6:12]))
                    iv, lat, to = struct.unpack('<3H', p[12:18] if sub == 0x01 else p[24:30])
                    if status == 0:
                        c = Conn(h, t, addr)
                        if pending_create:
                            c.events.append((t, pending_create))
                        c.events.append((t, f'connected: interval {us(iv, 1.25)} ms latency {lat} timeout {to * 10} ms'))
                        conns[h] = c
                    pending_create = None
                elif sub == 0x03 and len(p) >= 10:
                    status, h, iv, lat, to = struct.unpack('<BHHHH', p[1:10])
                    if (c := conns.get(h)):
                        c.events.append((t, f'connection update complete status {status}: interval {us(iv, 1.25)} ms latency {lat} timeout {to * 10} ms'))
                elif sub == 0x07 and len(p) >= 11:
                    h, mtx, ttx, mrx, trx = struct.unpack('<5H', p[1:11])
                    if (c := conns.get(h)):
                        c.events.append((t, f'data length: tx {mtx} B / {ttx} us, rx {mrx} B / {trx} us'))
                elif sub == 0x0C and len(p) >= 6:
                    if (c := conns.get(p[2] | p[3] << 8)):
                        c.events.append((t, f'PHY update status {p[1]}: tx {p[4]} rx {p[5]}'))
            elif code == 0x13:  # Number Of Completed Packets
                for i in range(p[0]):
                    h, n = struct.unpack('<HH', p[1 + 4 * i:5 + 4 * i])
                    if (c := conns.get(h & 0xfff)):
                        c.flow.append((t, -n, False))
            elif code == 0x05 and len(p) >= 4:
                status, h, reason = struct.unpack('<BHB', p[:4])
                if (c := conns.pop(h & 0xfff, None)):
                    c.events.append((t, f'disconnected reason 0x{reason:02x}'))
                    c.t1 = t
                    closed.append(c)
            elif code == 0x0E and len(p) >= 7:
                op = p[1] | p[2] << 8
                if op in (0x2002, 0x2060) and p[3] == 0:
                    acl_buffers = struct.unpack('<HB', p[4:7])
        elif typ == 0x02 and len(d) >= 5:
            hf, ln = struct.unpack('<HH', d[1:5])
            c = conns.get(hf & 0xfff)
            if not c:
                continue
            payload, first = d[5:], ((hf >> 12) & 3) in (0, 2)
            write = False
            if first and len(payload) >= 5:
                cid, op = payload[2] | payload[3] << 8, payload[4]
                if sent:
                    c.cids[cid] += 1
                if cid == 4:
                    c.att_ops[('tx' if sent else 'rx', op)] += 1
                    c.mtu_requests += sent and op == 0x02
                    if sent and op == 0x52 and len(payload) >= 7:
                        write = True
                        c.writes.append((t, payload[5] | payload[6] << 8))
            if sent:
                c.flow.append((t, 1, write))
    for c in conns.values():
        c.t1 = recs[-1][0]
        closed.append(c)
    return sorted(closed, key=lambda c: c.t0), radio, acl_buffers


def bursts(c):
    groups, cur = [], []
    for w in c.writes:
        if cur and w[0] - cur[-1][0] > BURST_GAP_S:
            groups.append(cur)
            cur = []
        cur.append(w)
    if cur:
        groups.append(cur)
    return [g for g in groups if len(g) >= BURST_MIN_WRITES]


def analyse(c, g):
    """Controller occupancy during a burst: [first, last Write Command], packets in flight counted since the connection"""
    t0, t1 = g[0][0], g[-1][0]
    inflight, timeline = 0, []
    for t, delta, write in c.flow:
        inflight += delta
        if t0 <= t <= t1:
            timeline.append((t, inflight, delta))
    peak = max(x[1] for x in timeline)
    full = not_full = 0.0
    refills, waiting = [], None
    stalls, stall_s, last_completion = 0, 0.0, t0
    for k, (t, n, delta) in enumerate(timeline):
        if k + 1 < len(timeline):
            dt = timeline[k + 1][0] - t
            if n >= peak:
                full += dt
            else:
                not_full += dt
        if delta < 0:
            gap = t - last_completion
            if gap >= STALL_S:
                stalls += 1
                stall_s += gap
            last_completion = t
            if waiting is None and n < peak:
                waiting = t
        elif waiting is not None and n >= peak:
            refills.append(t - waiting)
            waiting = None
    # Delivered: last completion of the packets sent up to the last Write Command
    sent = sum(1 for t, d, w in c.flow if d > 0 and t <= t1)
    done, delivered = 0, t1
    for t, d, w in c.flow:
        if d < 0:
            done -= d
            if done >= sent:
                delivered = t
                break
    gaps = sorted(g[k + 1][0] - g[k][0] for k in range(len(g) - 1))
    refills.sort()
    q = lambda v, p: v[min(len(v) - 1, int(len(v) * p))] * 1000 if v else 0
    total = (full + not_full) or 1
    return dict(t0=t0, handle=g[0][1], writes=len(g), host_ms=(t1 - t0) * 1000, delivered_ms=(delivered - t0) * 1000, peak=peak,
                min_inflight=min(x[1] for x in timeline[1:]) if len(timeline) > 1 else peak, full_pct=full / total * 100,
                not_full_ms=not_full * 1000, refill_p50=q(refills, .5), refill_p99=q(refills, .99), stalls=stalls, stall_ms=stall_s * 1000,
                gap_p50=q(gaps, .5), gap_p90=q(gaps, .9), gap_max=q(gaps, 1))


def main():
    files = [a for a in sys.argv[1:] if not a.startswith('--')]
    tz = next((float(a.split('=')[1]) for a in sys.argv[1:] if a.startswith('--tz=')), 0)
    zone = datetime.timezone(datetime.timedelta(hours=tz))
    fmt = lambda t: datetime.datetime.fromtimestamp(t, zone).strftime('%H:%M:%S.%f')[:-3]
    recs = sorted((r for p in files for r in records(p)), key=lambda r: r[0])
    conns, radio, acl_buffers = parse(recs)

    print(f'controller LE ACL buffers (size, count): {acl_buffers}')
    for c in conns:
        if not c.writes:
            continue
        print(f'\n=== connection 0x{c.handle:03x} {c.addr} {fmt(c.t0)} -> {fmt(c.t1)} ({c.t1 - c.t0:.1f} s)')
        for t, e in c.events:
            print(f'  {fmt(t)} {e}')
        before = {}
        for t, kind, on in radio:
            if t < c.t0:
                before[kind] = f'{kind} {"on" if on else "off"} since {fmt(t)}'
        during = [f'{fmt(t)} {kind} {"on" if on else "off"}' for t, kind, on in radio if c.t0 <= t <= c.t1]
        print(f'  scan / advertising at the connection: {", ".join(before.values()) or "never set"}; during it: {", ".join(during) or "no change"}')
        print(f'  MTU requests sent: {c.mtu_requests}; L2CAP channels used by the host: {dict(c.cids)} (0x0004 = ATT, others = EATT or CoC)')
        print('  ATT opcodes: ' + ', '.join(f'{d} 0x{o:02x}: {n}' for (d, o), n in sorted(c.att_ops.items())))
        for g in bursts(c):
            b = analyse(c, g)
            print(f'  burst {fmt(b["t0"])} handle 0x{b["handle"]:04x}: {b["writes"]} Write Commands in {b["host_ms"]:.0f} ms, delivered after {b["delivered_ms"]:.0f} ms')
            print(f'    controller full ({b["peak"]} packets) {b["full_pct"]:.1f} % of the time, never below {b["min_inflight"]}; host refill after a completion '
                  f'p50 {b["refill_p50"]:.2f} ms p99 {b["refill_p99"]:.2f} ms')
            print(f'    radio stalls (no completion for {STALL_S * 1000:.0f} ms or more): {b["stalls"]}, {b["stall_ms"]:.0f} ms; gaps between writes '
                  f'p50 {b["gap_p50"]:.2f} p90 {b["gap_p90"]:.2f} max {b["gap_max"]:.1f} ms')
            print('BURST\t' + '\t'.join(str(round(b[k], 3)) for k in ('t0', 'handle', 'writes', 'host_ms', 'delivered_ms', 'full_pct', 'not_full_ms',
                                                                        'refill_p50', 'refill_p99', 'min_inflight', 'stalls', 'stall_ms', 'gap_p50')))


if __name__ == '__main__':
    main()
