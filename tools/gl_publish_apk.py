#!/usr/bin/env python3
"""Создаёт Generic Package в GitLab для передачи APK.

Использование:
  python3 tools/gl_publish_apk.py app/build/outputs/apk/release/app-release.apk 0.3.0

Печатает ссылку для скачивания, сумму `sha256` и размер — их надо вписать в
`update.json` (поля `url`, `sha256`, `size`). Токен берётся из
`~/.config/dexp-m8/git-push-token` и в вывод не попадает.

Токену нужен scope **`api`**: пакеты — это `/projects/:id/packages/generic/...`,
а `write_repository` (его хватает на git push) даёт на этом пути
`403 insufficient_scope`. Токен создаётся в GitLab: «Настройки → Токены доступа»,
галочка `api`; положить в тот же файл (права 600). Пока scope нет — публиковать
нечем, B76 скачать такой APK не сможет.
"""
import hashlib
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
        body = e.read().decode()
        print(body[:400])
        if e.code == 403 and "insufficient_scope" in body:
            print()
            print("Токену не хватает scope `api` — публикация пакетов закрыта.")
            print("См. описание в начале этого файла: новый токен с `api` в")
            print("~/.config/dexp-m8/git-push-token.")
        raise SystemExit(1)


def main() -> None:
    path = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
    version = sys.argv[2] if len(sys.argv) > 2 else "0.1.0"
    name = os.path.basename(path)

    # Сумма и размер считаются до загрузки: они нужны в `update.json`, а если
    # загрузка не пройдёт (403 без scope `api`) — останутся под рукой.
    with open(path, "rb") as f:
        data = f.read()
    sha = hashlib.sha256(data).hexdigest()
    size = len(data)
    print(f"apk:     {path}")
    print(f"sha256:  {sha}")
    print(f"size:    {size}")
    print()

    upload(path, version, name)
    print()
    print("=== ссылка для скачивания (открыть в браузере, будучи залогиненным) ===")
    print(f"https://git.ogrpfsense.ru/denisle/dexp-m8/-/packages/{version}")
    print()
    print("=== прямая ссылка на файл (сюда смотрит `url` в update.json) ===")
    print(f"https://git.ogrpfsense.ru/api/v4/projects/{PROJECT}/packages/generic/dexp-m8/{version}/{name}")
    print()
    print("=== поля для update.json ===")
    print(json.dumps({"url": f"https://git.ogrpfsense.ru/api/v4/projects/{PROJECT}"
                             f"/packages/generic/dexp-m8/{version}/{name}",
                      "sha256": sha, "size": size},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
