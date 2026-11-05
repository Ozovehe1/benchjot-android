#!/usr/bin/env bash
# The web links to the newest APK through GitHub's stable redirect
#   https://github.com/<repo>/releases/latest/download/benchjot.apk
# (the web's ANDROID_APK_URL). That works only while every release is a full release (not a
# draft or prerelease), is the latest, and carries an asset named exactly benchjot.apk.
# Fails if any of that changed, or if the redirect doesn't reach this release's asset.
#   check-release.sh <tag> [owner/repo]     needs gh (GH_TOKEN) and curl
set -euo pipefail
TAG=${1:?tag}
REPO=${2:-${GITHUB_REPOSITORY:-Ozovehe1/benchjot-android}}
ASSET=benchjot.apk
URL="https://github.com/$REPO/releases/latest/download/$ASSET"
fail() { echo "::error::$1"; exit 1; }

info=$(gh api "repos/$REPO/releases/tags/$TAG") && [ -n "$info" ] || fail "No release $TAG"
[ "$(jq -r .draft <<<"$info")" = false ] || fail "$TAG is a draft: the web's APK link skips drafts"
[ "$(jq -r .prerelease <<<"$info")" = false ] || fail "$TAG is a prerelease: the web's APK link skips prereleases"
size=$(jq -r --arg a "$ASSET" '[.assets[] | select(.name == $a)][0].size // empty' <<<"$info")
[ -n "$size" ] || fail "$TAG has no asset named exactly $ASSET (has: $(jq -r '[.assets[].name] | join(", ")' <<<"$info"))"
latest=$(gh api "repos/$REPO/releases/latest" --jq .tag_name)
[ "$latest" = "$TAG" ] || fail "The latest release is $latest, not $TAG"

# The stable URL's first hop (a HEAD request) names the tag it serves. The storage host refuses
# HEAD on its signed URLs (401), so the file itself is read as its first byte, which also gives its size.
location=$(curl -sS -o /dev/null -w '%{redirect_url}' -I "$URL")
[ "$location" = "https://github.com/$REPO/releases/download/$TAG/$ASSET" ] || fail "$URL redirects to '$location', not $TAG's $ASSET"
read -r code range < <(curl -sSL -r 0-0 -o /dev/null -w '%{http_code} %header{content-range}\n' "$URL")
[ "$code" = 206 ] || fail "$URL ends in HTTP $code"
[ "${range##*/}" = "$size" ] || fail "$URL serves ${range##*/} bytes, $TAG's $ASSET is $size"
echo "$URL -> $TAG/$ASSET ($size bytes, full release, latest)"
