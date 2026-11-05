#!/usr/bin/env bash
# Restores the newest unexpired artifact called <name> from any run of this repo (any branch)
# into <dir>, and prints hit=true|false for $GITHUB_OUTPUT:
#
#   scripts/restore-artifact.sh <name> <dir>
#
# The build cache for what takes hours (CPython for armeabi-v7a, the NumPy stack per ABI): a job
# that builds one uploads it as an artifact named by its key (the hashes of its inputs), and any
# later run restores it from there. Not actions/cache: a branch's cache can't be restored on main,
# so the release run after a merge would rebuild what the branch already built from the same
# sources. Artifacts expire after the repo's retention (90 days); then the job builds again.
# Needs: curl, python3, unzip; GH_TOKEN (actions: read) and GITHUB_REPOSITORY.
set -euo pipefail
NAME=${1:?name}; DIR=${2:?dir}
REPO=${GITHUB_REPOSITORY:-Ozovehe1/benchjot-android}
[[ $NAME =~ ^[A-Za-z0-9._-]+$ ]] || { echo "bad artifact name: $NAME" >&2; exit 1; }
gh_curl() { curl -fsSL --retry 3 ${GH_TOKEN:+-H "Authorization: Bearer $GH_TOKEN"} -H "X-GitHub-Api-Version: 2022-11-28" "$@"; }
URL=$(gh_curl "https://api.github.com/repos/$REPO/actions/artifacts?name=$NAME&per_page=20" | python3 -c '
import json, sys
name = sys.argv[1]
live = [a for a in json.load(sys.stdin)["artifacts"] if a["name"] == name and not a["expired"]]
print(max(live, key=lambda a: a["created_at"])["archive_download_url"] if live else "")' "$NAME")
if [ -z "$URL" ]; then echo "no artifact $NAME" >&2; echo "hit=false"; exit 0; fi
T=$(mktemp); gh_curl -o "$T" "$URL"   # redirects to storage (curl drops the token there)
mkdir -p "$DIR"; unzip -q -o "$T" -d "$DIR"; rm -f "$T"
echo "restored $NAME into $DIR ($(du -sh "$DIR" | cut -f1))" >&2; echo "hit=true"
