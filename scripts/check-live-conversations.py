#!/usr/bin/env python3
"""Opt-in Meta evaluation with synthetic data and disposable PostgreSQL (Docker required)."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
values = {}
if (root / '.env').exists():
    for line in (root / '.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            name, value = line.split('=', 1)
            values[name.strip()] = value.strip().strip('"').strip("'")
key = os.environ.get('META_API_KEY') or values.get('META_API_KEY')
if not key:
    raise SystemExit('Set META_API_KEY in .env or your environment first. Do not paste it into chat.')
env = os.environ.copy()
env.update(RUN_META_LIVE_EVAL='true', META_EVAL_KEY=key)
print('Running live Meta inference with the app prompt and synthetic conversations. Test PostgreSQL is disposable; WhatsApp sends are disabled.', flush=True)
raise SystemExit(subprocess.run(['./mvnw', '-q', '-Dtest=LiveConversationEvaluationTest', 'test'], cwd=root, env=env).returncode)
