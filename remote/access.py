#!/usr/bin/env python3
"""Operator-only Cloudflare client. Management credentials never leave this host."""
import argparse, base64, hashlib, json, os, pathlib, re, secrets, subprocess, time, urllib.request, urllib.error
ROOT = pathlib.Path.home() / '.forge'
PRIVATE = ROOT / 'remote'
PRIVATE.mkdir(mode=0o700, exist_ok=True)
os.chmod(PRIVATE, 0o700)
CONTEXT = ROOT / 'cloudflare-context.json'

def save(path, value):
    temporary = pathlib.Path(str(path) + '.tmp')
    with open(temporary, 'w', opener=lambda p,f: os.open(p, f, 0o600)) as file:
        file.write(json.dumps(value, indent=2) + '\n')
    os.replace(temporary, path)

def api(method, path, value=None):
    request = urllib.request.Request('https://api.cloudflare.com/client/v4' + path,
        data=json.dumps(value).encode() if value is not None else None, method=method,
        headers={'Authorization': 'Bearer ' + (ROOT / 'cloudflare-api-token').read_text().strip(), 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response: result = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Cloudflare {error.code}: {error.read().decode()}') from None
    if not result.get('success'): raise RuntimeError(str(result.get('errors')))
    return result.get('result')

def setup():
    c = json.loads(CONTEXT.read_text()); account = '/accounts/' + c['account_id']
    organization = api('GET', account + '/access/organizations')
    c['team'] = organization['auth_domain'].removesuffix('.cloudflareaccess.com')
    if not c.get('app_id'):
        apps = api('GET', account + '/access/apps')
        match = next((x for x in apps if x.get('domain') == c['hostname']), None)
        if match and match.get('name') != 'Forge mobile': raise RuntimeError('Hostname already has another Access app; refusing to change it.')
        # No device policy is attached until an individual phone is approved.
        app = match or api('POST', account + '/access/apps', {'name':'Forge mobile','domain':c['hostname'],'type':'self_hosted',
            'session_duration':'1h','app_launcher_visible':False,'auto_redirect_to_identity':False,'service_auth_401_redirect':True})
        c['app_id'] = app['id']; c['aud'] = app['aud']; save(CONTEXT,c)
    if not c.get('tunnel_id'):
        tunnel_secret = base64.b64encode(secrets.token_bytes(32)).decode()
        tunnel = api('POST', account + '/cfd_tunnel', {'name':'forge-mobile','config_src':'local','tunnel_secret':tunnel_secret})
        save(PRIVATE / 'tunnel.json', {'AccountTag':c['account_id'],'TunnelSecret':tunnel_secret,'TunnelID':tunnel['id']})
        c['tunnel_id'] = tunnel['id']; save(CONTEXT,c)
    save(PRIVATE / 'cloudflared.json', {'tunnel':c['tunnel_id'],'credentials-file':str(PRIVATE / 'tunnel.json'),
        'metrics':'127.0.0.1:38432','ingress':[{'hostname':c['hostname'],'service':'http://127.0.0.1:38431',
            'originRequest':{'access':{'required':True,'teamName':c['team'],'audTag':[c['aud']]}}}, {'service':'http_status:404'}]})
    if not (PRIVATE / 'devices.json').exists(): save(PRIVATE / 'devices.json', {'devices':[]})
    print('Created deny-by-default Access app and dedicated Forge tunnel. DNS is not published yet.')

def approve(name, output):
    c=json.loads(CONTEXT.read_text())
    output=pathlib.Path(output).expanduser().resolve()
    if output.exists(): raise ValueError('Refusing to overwrite an existing connection file.')
    if not output.parent.is_dir(): raise ValueError('Create the private output directory first.')
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,60}', name): raise ValueError('Use a short phone label containing letters, numbers, hyphens, or underscores.')
    state=json.loads((PRIVATE/'devices.json').read_text())
    if any(d['name']==name for d in state['devices']): raise ValueError('Device already approved. Revoke it before renewing.')
    credential=api('POST',f"/accounts/{c['account_id']}/access/service_tokens",{'name':'forge-'+name,'duration':'2160h'})
    try:
        policy=api('POST',f"/accounts/{c['account_id']}/access/apps/{c['app_id']}/policies",{
            'name':'forge-'+name,'decision':'non_identity','include':[{'service_token':{'token_id':credential['id']}}]})
    except Exception:
        api('DELETE',f"/accounts/{c['account_id']}/access/service_tokens/{credential['id']}")
        raise
    token=secrets.token_urlsafe(32)
    state['devices'].append({'name':name,'policy_id':policy['id'],'service_token_id':credential['id'],
        'token_sha256':hashlib.sha256(token.encode()).hexdigest(),'expires':int((time.time()+90*86400)*1000)})
    save(PRIVATE/'devices.json',state)
    save(pathlib.Path(output),{'server':'https://'+c['hostname'],'token':token,
        'access':{'clientId':credential['client_id'],'clientSecret':credential['client_secret']}})
    print('Approved',name,'for 90 days. Import the private connection file in Forge; then delete the transfer copy.')

def revoke(name):
    c=json.loads(CONTEXT.read_text()); state=json.loads((PRIVATE/'devices.json').read_text())
    device=next(d for d in state['devices'] if d['name']==name)
    state['devices']=[d for d in state['devices'] if d['name']!=name]; save(PRIVATE/'devices.json',state)
    # Close active sockets before external API work, even if Cloudflare is unavailable.
    subprocess.run(['systemctl','--user','restart','forge-remote.service'],check=True)
    api('DELETE',f"/accounts/{c['account_id']}/access/apps/{c['app_id']}/policies/{device['policy_id']}")
    api('DELETE',f"/accounts/{c['account_id']}/access/service_tokens/{device['service_token_id']}")
    print('Device revoked; existing remote connections closed.')

def publish():
    c=json.loads(CONTEXT.read_text())
    records=api('GET',f"/zones/{c['zone_id']}/dns_records?name={c['hostname']}")
    target=c['tunnel_id']+'.cfargotunnel.com'
    if records:
        if len(records)!=1 or records[0].get('content')!=target or not records[0].get('proxied'): raise RuntimeError('Existing DNS differs; refusing to overwrite it.')
    else: api('POST',f"/zones/{c['zone_id']}/dns_records",{'type':'CNAME','name':c['hostname'],'content':target,'proxied':True,'ttl':1})
    print('Published https://'+c['hostname']+' behind Access.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(); sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('setup'); sub.add_parser('publish')
    p=sub.add_parser('approve'); p.add_argument('name'); p.add_argument('output')
    p=sub.add_parser('revoke'); p.add_argument('name')
    args=parser.parse_args()
    if args.command=='setup': setup()
    elif args.command=='approve': approve(args.name,args.output)
    elif args.command=='revoke': revoke(args.name)
    else: publish()
