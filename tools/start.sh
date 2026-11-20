#!/usr/bin/env bash
# Старт задачи: состояние репозитория, «СЛЕДУЮЩИЙ ШАГ», первая запись журнала.
# Использование: bash tools/start.sh        — Act (с git pull --ff-only)
#                bash tools/start.sh plan   — Plan (без правок и слияния)
set -u
cd "$(dirname "$0")/.." || exit 1
if [ "${1:-}" != "plan" ]; then
  GIT_ASKPASS=$PWD/tools/git-askpass.sh GIT_TERMINAL_PROMPT=0 git pull --ff-only 2>&1
fi
git status -sb
git config user.name
echo '--- CONTEXT с 12-й строки'
sed -n '12,$p' docs/CONTEXT.md
echo '--- DEVLOG: первая запись'
grep -n '^## ' docs/DEVLOG.md | head -2
