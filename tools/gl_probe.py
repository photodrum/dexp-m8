#!/usr/bin/env python3
"""Проверка git-over-HTTPS для GitLab с fine-grained PAT из конфига Cline.

Токен не печатается и в git config не попадает: передаём его только
через временный askpass-скрипт окружения.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

CFG = os.path.expanduser("~/.cline/data/settings/cline_mcp_settings.json")
HOST = "git.ogrpfsense.ru"


def token() -> str:
    with open(CFG) as f:
        cfg = json.load(f)
    auth = cfg["mcpServers"]["gitlab"]["headers"]["Authorization"]
    return auth.split("Bearer", 1)[-1].strip()


def main() -> int:
    tok = token()
    if not tok:
        print("токен не найден в конфиге")
        return 2

    tmp = tempfile.mkdtemp(prefix="gl-")
    askpass = os.path.join(tmp, "askpass.sh")
    with open(askpass, "w") as f:
        f.write("#!/bin/sh\n")
        f.write('case "$1" in\n')
        f.write("  *[Uu]sername*) echo oauth2 ;;\n")
        f.write(f"  *) echo '{tok}' ;;\n")
        f.write("esac\n")
    os.chmod(askpass, 0o700)

    env = dict(os.environ)
    env["GIT_ASKPASS"] = askpass
    env["GIT_TERMINAL_PROMPT"] = "0"

    url = sys.argv[1] if len(sys.argv) > 1 else f"https://{HOST}/mcp-agent/dexp-m8.git"
    print(f"проверяю: {url}")
    r = subprocess.run(
        ["git", "ls-remote", "--heads", url],
        env=env, capture_output=True, text=True, timeout=25,
    )
    out = (r.stdout or "").strip()
    err = (r.stderr or "").strip()
    print(f"exit={r.returncode}")
    if out:
        print("ветки:\n" + out)
    if err:
        print("stderr: " + err.replace(tok, "***"))

    shutil.rmtree(tmp, ignore_errors=True)
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
