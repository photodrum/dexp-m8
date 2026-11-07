#!/usr/bin/env python3
"""Создаёт Generic Package в GitLab для передачи APK.

Использование:
  python3 tools/gl_publish_apk.py app/build/outputs/apk/debug/app-debug.apk 0.1.0

Печатает ссылку на скачивание. Токен берётся из ~/.config/dexp-m8/git-push-token
и в вывод не попадает.
"""
import json
import os
import ssl
import sys
import urllib.error
import urllib.request

BASE = "https://git.ogrpfsense.ru/api/v4"
PROJECT = "denisle%2Fdexp-m8"
TOKEN_FILE = os.path.expanduser("~/.config/dexp-m8/git-push-token")


def token() -> str:
    with open(TOKEN_FILE) as f:
        return f.read().strip()


def upload(path: str, version: str, package_file: str) -> None:
    with open(path, "rb") as f:
        data = f.read()

    url = f"{BASE}/projects/{PROJECT}/packages/generic/dexp-m8/{version}/{package_file}"
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Authorization": f"Bearer {token()}"},
        method="PUT",
    )
    try:
        with urllib.request.urlopen(req, timeout=120, context=ssl.create_default_context()) as r:
            body = json.loads(r.read().decode() or "{}")
            print("upload status:", r.status)
            print("file:", body.get("file_name"))
            print("size:", body.get("size"))
    except urllib.error.HTTPError as e:
        print("HTTP", e.code)
        print(e.read().decode()[:400])
        raise SystemExit(1)


def main() -> None:
    path = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk"
    version = sys.argv[2] if len(sys.argv) > 2 else "0.1.0"
    name = os.path.basename(path)
    upload(path, version, name)
    print()
    print("=== ссылка для скачивания (открыть в браузере, будучи залогиненным) ===")
    print(f"https://git.ogrpfsense.ru/denisle/dexp-m8/-/packages/{version}")
    print()
    print("=== прямая ссылка на файл ===")
    print(f"https://git.ogrpfsense.ru/api/v4/projects/{PROJECT}/packages/generic/dexp-m8/{version}/{name}")


if __name__ == "__main__":
    main()
