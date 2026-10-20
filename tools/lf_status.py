import os, json, urllib.request, sys

GH = os.environ.get('GH_TOKEN', '').strip()
def api(p):
    r = urllib.request.Request('https://api.github.com' + p,
        headers={'Authorization': 'Bearer ' + GH,
                 'Accept': 'application/vnd.github+json',
                 'User-Agent': 'lf'})
    return json.load(urllib.request.urlopen(r, timeout=45))

try:
    br = api('/repos/mouya-q/LiquidFrame/branches')
    for b in br:
        if b.get('name') == 'main':
            print('remote main HEAD:', b['commit']['sha'][:12])
            print('   msg:', b['commit']['commit']['message'].split('\n')[0][:80])
    runs = api('/repos/mouya-q/LiquidFrame/actions/runs?per_page=5').get('workflow_runs', [])
    for r in runs:
        sha = (r.get('head_sha') or '')[:8]
        msg = ((r.get('head_commit') or {}).get('message') or '').split('\n')[0][:60]
        print(r['id'], r.get('status'), r.get('conclusion'), '|', sha, '|', msg)
except Exception as e:
    print('ERR', type(e).__name__, e)
