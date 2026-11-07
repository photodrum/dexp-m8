#!/usr/bin/env python3
"""Проверка: может ли токен создать проект (POST /projects).

Пробный POST без имени — если прав нет, GitLab ответит insufficient_granular_scope
раньше, чем создаст что-либо. Токен не печатается.
"""
import json
import os
import ssl
import urllib.error
import urllib.request

CFG = os.path.expanduser("~/.cline/data/settings/cline_mcp_settings.json")
BASE = "https://git.ogrpfsense.ru/api/v4"


def token() -> str:
    with open(CFG) as f:
        cfg = json.load(f)
    return cfg["mcpServers"]["gitlab"]["headers"]["Authorization"].split("Bearer", 1)[-1].strip()


req = urllib.request.Request(
    BASE + "/projects",
    data=json.dumps({}).encode(),
    headers={
        "Authorization": "Bearer " + token(),
        "Content-Type": "application/json",
        "Accept": "application/json",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=20, context=ssl.create_default_context()) as r:
        print("status", r.status)
        print(r.read().decode()[:400])
except urllib.error.HTTPError as e:
    print("status", e.code)
    print(e.read().decode()[:400])
