# The bursts of snoop.py matched with the blocks of an A/B run of ab.sh (one connection per block, in order), then the
# medians per scenario and version:
#
#   python -I snoop.py btsnoop_hci.log.last btsnoop_hci.log > report.txt
#   python -I join.py ab_<name>.txt report.txt
#
# The snoop files keep only the most recent packets (about 2 x 9 MB on a Pixel): the blocks are matched from the end.
import collections
import statistics as st
import sys

COLUMNS = ('t0', 'handle', 'writes', 'host_ms', 'delivered_ms', 'full_pct', 'not_full_ms', 'refill_p50', 'refill_p99', 'min_inflight',
           'stalls', 'stall_ms', 'gap_p50')
# Scenario of each burst of 512 Write Commands, in the order of the A/B blocks (ab.sh SCENARIOS then ONLY_40)
NAMES = ('write_no_resp', 'ota_app', 'ota_lib')

blocks = [(p[3], int(p[5])) for p in (line.split() for line in open(sys.argv[1], encoding='utf-8')) if p[:1] == ['#']]
conns, cur = [], None
for line in open(sys.argv[2], encoding='utf-8'):
    if line.startswith('=== '):
        cur = []
        conns.append(cur)
    elif line.startswith('BURST') and cur is not None:
        cur.append(dict(zip(COLUMNS, map(float, line.rstrip('\n').split('\t')[1:]))))
conns = [c for c in conns if c]
n = min(len(conns), len(blocks))
print(f'{len(blocks)} blocks, {len(conns)} connections with bursts: the last {n} matched\n')
print('version round scenario       deliveredMs full%  refill p50/p99 ms  min  stalls stallMs')
agg = collections.defaultdict(lambda: collections.defaultdict(list))
for (v, r), bursts in zip(blocks[-n:], conns[-n:]):
    k = 0
    for b in bursts:
        writes = int(b['writes'])
        # Two scenarios less than 1 s apart make one burst of 1024
        name = NAMES[min(k, len(NAMES) - 1)] if writes == 512 else 'merged'
        k += max(1, writes // 512)
        print(f'{v:7} {r:5} {name:14} {b["delivered_ms"]:9.0f} {b["full_pct"]:6.1f}  {b["refill_p50"]:6.2f} / {b["refill_p99"]:5.2f}   {b["min_inflight"]:4.0f}'
              f' {b["stalls"]:6.0f} {b["stall_ms"]:7.0f}')
        if name != 'merged':
            for key in ('delivered_ms', 'full_pct', 'refill_p50', 'refill_p99', 'min_inflight', 'stalls', 'stall_ms'):
                agg[(name, v)][key].append(b[key])
print('\nmedians')
for (name, v), d in sorted(agg.items()):
    m = {k: st.median(x) for k, x in d.items()}
    print(f'{name:14} {v:7} n={len(d["delivered_ms"])}: delivered {m["delivered_ms"]:.0f} ms, controller full {m["full_pct"]:.1f} %, host refill '
          f'p50 {m["refill_p50"]:.2f} ms p99 {m["refill_p99"]:.2f} ms, never below {min(d["min_inflight"]):.0f} packets, radio stalls {m["stalls"]:.0f} '
          f'({m["stall_ms"]:.0f} ms)')
