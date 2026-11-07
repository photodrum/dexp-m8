#!/bin/sh
# Askpass для git: подставляет fine-grained PAT из конфига Cline.
# Токен не попадает ни в командную строку, ни в git config, ни в логи.
case "$1" in
  *[Uu]sername*) echo oauth2 ;;
  *)
    python3 -c "import json,os;p=os.path.expanduser('~/.cline/data/settings/cline_mcp_settings.json');f=open(p);c=json.load(f);print(c['mcpServers']['gitlab']['headers']['Authorization'].split('Bearer',1)[-1].strip())"
    ;;
esac
