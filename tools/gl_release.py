#!/usr/bin/env python3
"""Создаёт релиз GitLab `v<версия>` со ссылкой на APK из Generic Packages.

Использование (после `gl_publish_apk.py` и `git push`):
  python3 tools/gl_release.py 1.4.3
  python3 tools/gl_release.py 1.4.3 --ref=8e28771     # тег на конкретный коммит

Релиз виден в «Развёртывание → Релизы», а у файла появляется постоянный адрес, который
использует кнопка «Скачать APK» в README и который всегда ведёт на последнюю версию:
  https://git.ogrpfsense.ru/denisle/dexp-m8/-/releases/permalink/latest/downloads/app-release.apk

Тег ставится на `--ref` (по умолчанию — `HEAD`, он должен быть на GitLab). Текст релиза —
поле `notes` из `update.json`, если его `versionName` совпадает с версией. Если релиз уже
есть, обновляется только текст. Токен — `~/.config/dexp-m8/git-push-token`, нужен scope `api`.
"""
import json
import os
import ssl
import subprocess
import sys
import urllib.error
import urllib.request

BASE = "https://git.ogrpfsense.ru/api/v4"
PROJECT = "denisle%2Fdexp-m8"
SITE = "https://git.ogrpfsense.ru/denisle/dexp-m8"
TOKEN_FILE = os.path.expanduser("~/.config/dexp-m8/git-push-token")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_NAME = "app-release.apk"


def api(method, path, body=None):
    with open(TOKEN_FILE) as f:
        tok = f.read().strip()
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        f"{BASE}/projects/{PROJECT}{path}", data=data, method=method,
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60, context=ssl.create_default_context()) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw[:300]}


def notes(version):
    try:
        with open(os.path.join(ROOT, "update.json"), encoding="utf-8") as f:
            u = json.load(f)
        if u.get("versionName") == version and u.get("notes"):
            return u["notes"]
    except (OSError, ValueError):
        pass
    return f"DEXP M8 {version}"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__)
        sys.exit(2)
    version = args[0]
    tag = f"v{version}"
    ref = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--ref=")), None)
    if not ref:
        ref = subprocess.check_output(["git", "-C", ROOT, "rev-parse", "HEAD"], text=True).strip()

    code, cur = api("GET", f"/releases/{tag}")
    if code == 200:
        code, res = api("PUT", f"/releases/{tag}", {"description": notes(version)})
        print(f"релиз {tag} уже был — обновлён текст (HTTP {code})")
    else:
        pkg = f"{BASE}/projects/{PROJECT}/packages/generic/dexp-m8/{version}/{APK_NAME}"
        code, res = api("POST", "/releases", {
            "name": f"DEXP M8 {version}",
            "tag_name": tag,
            "ref": ref,
            "description": notes(version),
            "assets": {"links": [{
                "name": f"DEXP M8 {version} — {APK_NAME}",
                "url": pkg,
                "direct_asset_path": f"/{APK_NAME}",
                "link_type": "package",
            }]},
        })
        if code != 201:
            print(f"ОШИБКА: релиз не создан (HTTP {code}): {str(res)[:300]}")
            if code in (401, 403):
                print("Токену нужен scope `api` (см. tools/gl_publish_apk.py).")
            sys.exit(1)
        print(f"релиз {tag} создан на коммите {ref[:8]}")
    print(f"страница:       {SITE}/-/releases/{tag}")
    print(f"файл версии:    {SITE}/-/releases/{tag}/downloads/{APK_NAME}")
    print(f"файл последней: {SITE}/-/releases/permalink/latest/downloads/{APK_NAME}")


if __name__ == "__main__":
    main()
