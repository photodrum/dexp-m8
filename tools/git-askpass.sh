#!/bin/sh
# Askpass для git: отдаёт legacy-токен аккаунта denisle для https-операций.
#
# Почему отдельный файл, а не токен из конфига Cline:
#  - токен в конфиге MCP принадлежит mcp-agent и выдан только на "MCP tool: Execute",
#    ему git push запрещён (403 [Code: Push]);
#  - этот токен — личный legacy PAT с write_repository, только для git.
#
# Токен не попадает ни в командную строку, ни в git config, ни в логи.

TOKEN_FILE="$HOME/.config/dexp-m8/git-push-token"

case "$1" in
  *[Uu]sername*) echo denisle ;;
  *)
    if [ ! -r "$TOKEN_FILE" ]; then
      echo "нет $TOKEN_FILE" >&2
      exit 1
    fi
    # tr -d '\n': убираем возможный перевод строки, иначе GitLab вернёт 401
    tr -d '\r\n' < "$TOKEN_FILE"
    ;;
esac
