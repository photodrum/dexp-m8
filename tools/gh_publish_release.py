#!/usr/bin/env python3
"""Создаёт (или обновляет) релиз на GitHub-зеркале и прикладывает к нему APK.

Использование (после `git push` релизного коммита и `gl_publish_apk.py`):
  python3 tools/gh_publish_release.py app/build/outputs/apk/release/app-release.apk 1.4.3
  python3 tools/gh_publish_release.py app/build/outputs/apk/release/app-release.apk 1.4.3 --dry-run

Что делает:
  1. Берёт коммит `HEAD` (он обязан совпадать с `origin/main`) и ждёт, пока GitLab-зеркало
     довезёт его до GitHub (`--wait=N` минут, по умолчанию 20): тег на GitHub ставится на
     этот коммит, а не на более старый `main`.
  2. Создаёт релиз `v<версия>` (если он уже есть — обновляет текст и заменяет APK).
     Текст — поле `notes` из `update.json`, если его `versionName` совпадает с версией.
  3. Загружает APK как `dexp-m8-<версия>.apk`, скачивает его обратно и сверяет `sha256`.

`--dry-run` токен не требует: только показывает, дошёл ли коммит до GitHub и есть ли релиз.

Токен — fine-grained PAT аккаунта-владельца репозитория (`photodrum`): «Settings → Developer
settings → Personal access tokens → Fine-grained», репозиторий `dexp-m8`, право
**Contents: Read and write** (релизы и теги). Лежит в `~/.config/dexp-m8/github-token`
(права 600) или в переменной `GITHUB_TOKEN`. В вывод не попадает.

Ссылка для обновления на книге (`update.json`, B76) по-прежнему идёт на GitLab Packages;
релиз на GitHub — публичная копия APK.
"""
import hashlib
import json
import os
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = "photodrum/dexp-m8"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
TOKEN_FILE = os.path.expanduser("~/.config/dexp-m8/github-token")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CTX = ssl.create_default_context()
POLL_SECONDS = 30


def token():
    t = os.environ.get("GITHUB_TOKEN", "").strip()
    if t:
        return t
    try:
        with open(TOKEN_FILE) as f:
            return f.read().strip()
    except OSError:
        return ""


def request(method, url, tok, data=None, headers=None, timeout=120):
    """Возвращает (код, тело как dict/bytes). Ошибки HTTP — не исключение."""
    h = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "dexp-m8-release",
    }
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            body = r.read()
            code = r.status
    except urllib.error.HTTPError as e:
        body = e.read()
        code = e.code
    try:
        return code, json.loads(body.decode() or "{}")
    except ValueError:
        return code, body


def git(*args):
    return subprocess.check_output(["git", "-C", ROOT, *args], text=True).strip()


def fail(msg):
    print("ОШИБКА:", msg)
    sys.exit(1)


def release_notes(version):
    """`notes` из update.json, если он про эту версию."""
    try:
        with open(os.path.join(ROOT, "update.json"), encoding="utf-8") as f:
            u = json.load(f)
    except (OSError, ValueError):
        return f"DEXP M8 {version}"
    if u.get("versionName") == version and u.get("notes"):
        return u["notes"]
    return f"DEXP M8 {version}"


def wait_for_commit(sha, tok, minutes):
    """Ждёт, пока зеркало довезёт коммит до GitHub. True — коммит там."""
    deadline = time.time() + minutes * 60
    shown = False
    while True:
        code, _ = request("GET", f"{API}/repos/{REPO}/commits/{sha}", tok)
        if code == 200:
            return True
        if code not in (404, 422):
            fail(f"GitHub ответил {code} на проверку коммита {sha[:8]}")
        if time.time() >= deadline:
            return False
        if not shown:
            print(f"коммит {sha[:8]} ещё не в зеркале GitHub, жду (до {minutes} мин)…")
            shown = True
        time.sleep(POLL_SECONDS)


def parse_args():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    minutes = 20
    for a in sys.argv[1:]:
        if a.startswith("--wait="):
            minutes = int(a.split("=", 1)[1])
    if len(args) != 2:
        print(__doc__)
        sys.exit(2)
    return args[0], args[1], dry, minutes


def upload_release(tok, rel, version, head, exists, apk_data, asset_name):
    """Создаёт или обновляет релиз, заменяет APK. Возвращает (релиз, ссылка на APK)."""
    body = {
        "tag_name": f"v{version}",
        "target_commitish": head,
        "name": f"DEXP M8 {version}",
        "body": release_notes(version),
        "draft": False,
        "prerelease": False,
    }
    payload = json.dumps(body).encode()
    hdr = {"Content-Type": "application/json"}
    if exists:
        code, rel = request("PATCH", f"{API}/repos/{REPO}/releases/{rel['id']}", tok, payload, hdr)
        what = "обновлён"
    else:
        code, rel = request("POST", f"{API}/repos/{REPO}/releases", tok, payload, hdr)
        what = "создан"
    if code not in (200, 201):
        hint = ""
        if code in (401, 403, 404):
            hint = " Проверь токен: нужен fine-grained PAT на репозиторий с Contents: Read and write."
        fail(f"релиз не {what} (HTTP {code}): {str(rel)[:300]}.{hint}")
    print(f"релиз v{version} {what}: {rel['html_url']}")

    # Прежний файл с тем же именем — удалить, иначе GitHub ответит 422.
    for a in rel.get("assets", []):
        if a["name"] == asset_name:
            request("DELETE", f"{API}/repos/{REPO}/releases/assets/{a['id']}", tok)
            print(f"старый {asset_name} удалён")

    code, asset = request(
        "POST", f"{UPLOADS}/repos/{REPO}/releases/{rel['id']}/assets?name={asset_name}", tok,
        apk_data, {"Content-Type": "application/vnd.android.package-archive"}, timeout=300)
    if code != 201:
        fail(f"APK не загружен (HTTP {code}): {str(asset)[:300]}")
    return rel, asset["browser_download_url"]


def main():
    apk, version, dry, minutes = parse_args()
    if not os.path.isfile(apk):
        fail(f"нет файла {apk}")
    tag = f"v{version}"
    asset_name = f"dexp-m8-{version}.apk"

    with open(apk, "rb") as f:
        data = f.read()
    sha = hashlib.sha256(data).hexdigest()
    print(f"apk:     {apk}")
    print(f"sha256:  {sha}")
    print(f"size:    {len(data)}")

    head = git("rev-parse", "HEAD")
    if git("status", "--porcelain"):
        print("ПРЕДУПРЕЖДЕНИЕ: в рабочей папке есть незакоммиченное — тег встанет на HEAD.")
    if git("rev-parse", "origin/main") != head:
        fail("HEAD не равен origin/main: сначала `git push` релизного коммита.")
    print(f"коммит:  {head[:8]}  тег: {tag}")

    tok = token()
    if not dry and not tok:
        fail("нет токена GitHub: положи его в ~/.config/dexp-m8/github-token "
             "(инструкция — в начале tools/gh_publish_release.py) или задай GITHUB_TOKEN.")

    in_mirror = wait_for_commit(head, tok, 0 if dry else minutes)
    code, rel = request("GET", f"{API}/repos/{REPO}/releases/tags/{tag}", tok)
    exists = code == 200
    if dry:
        print("зеркало: коммит " + ("есть" if in_mirror else "ещё НЕ доехал"))
        print("релиз:   " + (f"{tag} уже есть ({rel.get('html_url')})" if exists
                             else f"{tag} не создан"))
        print("dry-run: ничего не менялось.")
        return
    if not in_mirror:
        fail(f"коммит {head[:8]} не доехал до GitHub за {minutes} мин: проверь зеркалирование "
             "в GitLab («Settings → Repository → Mirroring repositories», «Update now»).")

    rel, url = upload_release(tok, rel, version, head, exists, data, asset_name)
    print(f"APK загружен: {url}")

    # Контроль: скачать обратно (публичная ссылка, без токена) и сверить сумму.
    code, got = request("GET", url, "", headers={"Accept": "application/octet-stream"},
                        timeout=300)
    if code != 200 or not isinstance(got, bytes):
        fail(f"скачивание {url} вернуло HTTP {code}")
    got_sha = hashlib.sha256(got).hexdigest()
    if got_sha != sha:
        fail(f"sha256 скачанного файла {got_sha} не совпал с {sha}")
    print(f"проверка: скачано {len(got)} б, sha256 совпал")
    print()
    print("=== релиз на GitHub ===")
    print(rel["html_url"])


if __name__ == "__main__":
    main()
