#!/usr/bin/env python3
"""Проверка прав fine-grained PAT: какие scopes выданы и что видно.

Токен не печатается.
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


def get(path: str):
    req = urllib.request.Request(
        BASE + path,
        headers={"Authorization": "Bearer " + token(), "Accept": "application/json"},
    )
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
            return r.status, json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:300]
        return e.code, body
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def main() -> None:
    for path in (
        "/personal_access_tokens/self",
        "/groups/mcp-agents",
        "/groups/mcp-agents/members/all?per_page=20",
    ):
        code, data = get(path)
        print(f"--- {path} -> {code}")
        if isinstance(data, (dict, list)):
            if path.endswith("self"):
                keep = {k: data.get(k) for k in ("name", "scopes", "expires_at", "active", "revoked")}
                print(json.dumps(keep, ensure_ascii=False, indent=2))
            elif "members" in path:
                for m in data if isinstance(data, list) else []:
                    print(f"  {m.get('username')} access_level={m.get('access_level')}")
            else:
                keep = {k: data.get(k) for k in ("id", "full_path", "visibility", "web_url")}
                print(json.dumps(keep, ensure_ascii=False))
        else:
            print(str(data)[:300])


if __name__ == "__main__":
    main()
