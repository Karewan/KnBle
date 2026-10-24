# Round-by-round comparison of an A/B run of ab.sh against a reference build (3.0.5 by default): duration of each
# scenario per build and round, how many rounds each build was slower, mean gap.
#
#   python -I summary.py ab_<name>.txt [reference version]
import collections, statistics as st, sys

path = sys.argv[1]
ref = sys.argv[2] if len(sys.argv) > 2 else '3.0.5'
data = collections.defaultdict(dict)  # (scenario) -> {(version, round): kv}
order = collections.defaultdict(list)  # round -> versions in run order
for line in open(path, encoding='utf-8'):
    if line.startswith('# '):
        p = line.split()
        order[int(p[5])].append(p[3])
        continue
    p = line.rstrip('\n').split('\t')
    if len(p) < 4:
        continue
    kv = dict(x.split('=', 1) for x in p[3].split(' ') if '=' in x)
    data[p[2]][(p[0], int(p[1]))] = kv

metrics = {'write_no_response': 'dataSpanMs', 'write_with_response': 'dataSpanMs', 'ota_silabs': 'dataSpanMs', 'ota_silabs_lib': 'dataSpanMs',
           'notify_stream': 'ms'}
versions = sorted({v for d in data.values() for v, r in d}, key=lambda v: (v != ref, v))
rounds = sorted({r for d in data.values() for v, r in d})
print('run order:', {r: order[r] for r in rounds})
for scen, key in metrics.items():
    if scen not in data:
        continue
    d = data[scen]
    print(f'\n{scen} ({key}, pauses50 in brackets)')
    for v in versions:
        vals = [(r, int(d[(v, r)][key]), d[(v, r)].get('pauses50', '-')) for r in rounds if (v, r) in d and key in d[(v, r)]]
        if vals:
            print(f'  {v:8} ' + '  '.join(f'{x}[{p}]' for r, x, p in vals) + f'   median {st.median([x for r, x, p in vals]):.0f}')
    refs = {r: int(d[(ref, r)][key]) for r in rounds if (ref, r) in d and key in d[(ref, r)]}
    # The lib OTA is compared with the app OTA of the reference
    if scen == 'ota_silabs_lib':
        refs = {r: int(data['ota_silabs'][(ref, r)]['dataSpanMs']) for r in rounds if (ref, r) in data.get('ota_silabs', {})}
    for v in versions:
        if v == ref and scen != 'ota_silabs_lib':
            continue
        gaps = [(int(d[(v, r)][key]) - refs[r]) / refs[r] * 100 for r in rounds if r in refs and (v, r) in d and key in d[(v, r)]]
        if gaps:
            slower = sum(1 for g in gaps if g > 0)
            print(f'  {v} vs {ref}: slower in {slower}/{len(gaps)} rounds, gaps ' + ' '.join(f'{g:+.1f}%' for g in gaps) + f', mean {st.mean(gaps):+.1f}%')
