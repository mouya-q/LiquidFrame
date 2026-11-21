import os, json, urllib.request

GH = os.environ.get('GH_TOKEN', '').strip()
OWNER = 'mouya-q'
REPO = 'LiquidFrame'

def api(path):
    req = urllib.request.Request(
        'https://api.github.com' + path,
        headers={
            'Authorization': 'Bearer ' + GH,
            'Accept': 'application/vnd.github+json',
            'User-Agent': 'liquidframe-tool',
        },
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode('utf-8'))

try:
    ref = api('/repos/%s/%s/git/ref/heads/main' % (OWNER, REPO))
    sha = ref['object']['sha']
    print('main =', sha)
    commit = api('/repos/%s/%s/git/commits/%s' % (OWNER, REPO, sha))
    tree = commit['tree']['sha']
    files = api('/repos/%s/%s/git/trees/%s?recursive=1' % (OWNER, REPO, tree))
    names = [e['path'] for e in files['tree'] if e['type'] == 'blob']
    print('total blobs =', len(names))
    print('--- glass/ ---')
    for n in sorted(names):
        if '/glass/' in n:
            print(' ', n)
    print('--- PanelScan-related ---')
    for n in sorted(names):
        if 'PanelScan' in n:
            print(' ', n)
    print('--- any .backup / leak ---')
    bad = [n for n in names if '.backup' in n or n.startswith('.backup')]
    print('  ', bad if bad else 'clean')
except Exception as e:
    print('ERR', type(e).__name__, e)