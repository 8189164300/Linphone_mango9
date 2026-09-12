#!/bin/bash
set -eu
# Print paths only: never echo suspected private content into CI logs.
blocked='(^|/)(MANGO9_WHITELABEL_AUDIT|MANGO9_ANDROID_PARITY|IOS_ANDROID_PARITY_[0-9]+|APP_STORE_SUBMISSION|PLAY_STORE_SUBMISSION|CHAT_PUSH_NAVIGATION_FIX)\.md$|^docs/mobile-appointments-ios\.md$'
paths=$(git ls-files | grep -E "$blocked" || true)
infra=$(git grep --cached -IlE 'root@[[:alnum:]]|/etc/(opensips|flexisip|freeswitch)|/opt/(mango9|provision)|serv123|[Pp]roxmox' -- '*.md' || true)
if [ -n "$paths$infra" ]; then
  printf '%s\n' 'Public documentation check failed. Review these paths privately:' "$paths" "$infra"
  exit 1
fi
printf '%s\n' 'Public documentation check passed (not a replacement for secret scanning or diff review).'
