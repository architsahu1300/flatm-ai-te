#!/bin/bash
# Click "Code to clipboard" in Stitch, then run this. Auto-numbers each capture.
#   ./grab.sh          -> screen-01.html, screen-02.html, ...
#   ./grab.sh dark     -> screen-03-dark.html  (suffix any label you like)
cd "$(dirname "$0")" || exit 1

suffix=""
[ -n "$1" ] && suffix="-$1"

n=$(printf "%02d" $(( $(ls -1 screen-*.html 2>/dev/null | wc -l) + 1 )))
file="screen-${n}${suffix}.html"
pbpaste > "$file"

bytes=$(wc -c < "$file" | tr -d ' ')
if [ "$bytes" -lt 200 ]; then
  echo "⚠  $file is only ${bytes} bytes — clipboard may not have had the code. Re-copy and retry."
  exit 1
fi
echo "✓ saved $file (${bytes} bytes)"
