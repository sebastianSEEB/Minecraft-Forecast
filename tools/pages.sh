#!/usr/bin/env bash
# Shared gh-pages helpers for the data workflows.
#   tools/pages.sh clone DIR        clone the gh-pages branch into DIR
#   tools/pages.sh push DIR         commit DIR as one amended "data update" commit and force-push it
# One amended commit keeps the branch from growing with every hourly run.
set -euo pipefail
url="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
case "$1" in
  clone)
    rm -rf "$2"
    git clone -q --depth 2 --branch gh-pages "$url" "$2" ;;
  push)
    cd "$2"
    git config user.name "github-actions[bot]"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
    git add -A
    if [ "$(git log -1 --format=%s)" = "data update" ]; then git commit -q --amend -m "data update"
    else git commit -q -m "data update"; fi
    git push -q --force-with-lease origin gh-pages ;;
esac
