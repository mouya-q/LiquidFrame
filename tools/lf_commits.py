import os, json, urllib.request

GH = os.environ.get('GH_TOKEN', '').strip()

def api(p):
    r = urllib.request.Request('https://api.github.com' + p,
        headers={'Authorization': 'Bearer ' + GH,
                 'Accept': 'application/vnd.github+json',
                 'User-Agent': 'lf'})
    return json.load(urllib.request.urlopen(r, timeout=45))

try:
    commits = api('/repos/mouya-q/LiquidFrame/commits?sha=main&per_page=8')
    for c in commits:
        msg = (c.get('commit', {}).get('message') or '').split('\n')[0][:58]
        print(c['sha'], '|', msg)
except Exception as e:
    print('ERR', type(e).__name__, e)