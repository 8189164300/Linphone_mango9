#!/bin/bash
set -eu
# Print paths only; never echo suspected private content into build logs.
failed=0
while IFS= read -r path; do
  case "$path" in
    *MANGO9_WHITELABEL_AUDIT.md|*MANGO9_ANDROID_PARITY.md|*IOS_ANDROID_PARITY_*.md|*APP_STORE_SUBMISSION.md|*PLAY_STORE_SUBMISSION.md|*CHAT_PUSH_NAVIGATION_FIX.md|docs/mobile-appointments-ios.md)
      printf '%s\n' "$path"
      failed=1
      ;;
  esac
done < <(git ls-files -- '*.md')
if git grep --cached -IlE 'root@[[:alnum:]]|/etc/(opensips|flexisip|freeswitch)|/opt/(mango9|provision)|serv123|[Pp]roxmox' -- '*.md'; then
  failed=1
fi
if [ "$failed" -ne 0 ]; then
  printf '%s\n' 'Public documentation check failed. Review the listed paths privately.'
  exit 1
fi
printf '%s\n' 'Public documentation check passed (not a replacement for secret scanning or diff review).'
