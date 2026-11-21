import os, json, urllib.request

GH = os.environ.get('GH_TOKEN', '').strip()


def api(p):
    r = urllib.request.Request(
        'https://api.github.com' + p,
        headers={'Authorization': 'Bearer ' + GH,
                 'Accept': 'application/vnd.github+json',
                 'User-Agent': 'lf'})
    return json.load(urllib.request.urlopen(r, timeout=45))


def main():
    try:
        runs = api('/repos/mouya-q/LiquidFrame/actions/runs?per_page=6')
    except Exception as e:
        print('ERR runs', type(e).__name__, e)
        return
    lst = runs.get('workflow_runs') or []
    if not lst:
        print('no runs; raw keys:', list(runs.keys())[:8])
        return
    for r in lst:
        hc = r.get('head_commit') or {}
        msg = (hc.get('message') or '').split('\n')[0][:56]
        print('{} {:>9} {:>10} | {} | {}'.format(
            r.get('id'), r.get('status'), r.get('conclusion') or '-',
            (r.get('head_sha') or '')[:8], msg))


main()