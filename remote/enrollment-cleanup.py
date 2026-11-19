#!/usr/bin/env python3
"""Remove the temporary bootstrap exception; the main Access application is untouched."""
import json, subprocess
import access
context_file = access.PRIVATE / 'enrollment-context.json'
if not context_file.exists(): raise SystemExit(0)
context = json.loads(context_file.read_text())
try:
    access.api('DELETE', f"/accounts/{context['account_id']}/access/apps/{context['app_id']}")
finally:
    config_file = access.PRIVATE / 'cloudflared.json'
    config = json.loads(config_file.read_text())
    config['ingress'] = [rule for rule in config['ingress'] if not (
        rule.get('hostname') == context['hostname'] and rule.get('path') == context['path'] and
        rule.get('service') == 'http://127.0.0.1:38434')]
    access.save(config_file, config)
    subprocess.run(['systemctl', '--user', 'restart', 'forge-tunnel.service'], check=True)
    subprocess.run(['systemctl', '--user', 'stop', 'forge-enrollment-download.service'], check=False, capture_output=True)
    (access.PRIVATE / 'enrollment-ticket.json').unlink(missing_ok=True)
    context_file.unlink(missing_ok=True)
