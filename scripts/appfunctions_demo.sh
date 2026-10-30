#!/usr/bin/env bash
# Hackathon fallback demo: drives Pocket Casts AppFunctions over adb, the same calls an agent makes for
# "I'm heading out for a run. Play the latest Radiolab, queue the newest Still Counting after it,
#  and stop playing in 45 minutes."
#
# Usage: scripts/appfunctions_demo.sh [package]   (default: au.com.shiftyjelly.pocketcasts.debug)
set -euo pipefail

PACKAGE="${1:-au.com.shiftyjelly.pocketcasts.debug}"
SERVICE="au.com.shiftyjelly.pocketcasts.appfunctions.PocketCastsAppFunctionService"

call() {
    local function="$1" params="$2"
    echo "→ $function $params" >&2
    adb shell cmd app_function execute-app-function \
        --package "$PACKAGE" --function "$SERVICE#$function" --parameters "'$params'" --brief-yaml
}

latest_episode_uuid() {
    call searchEpisodes "{\"podcastTitle\":\"$1\",\"limit\":1}" | awk '/episodeUuid:/ { print $2; exit }'
}

pause() { sleep "${DEMO_PAUSE:-2}"; }

echo "1. Find the latest Radiolab and Still Counting episodes"
PLAY_UUID="$(latest_episode_uuid "Radiolab")"
QUEUE_UUID="$(latest_episode_uuid "Still Counting")"
pause

echo "2. Play Radiolab now"
call playEpisode "{\"episodeUuid\":\"$PLAY_UUID\"}" | grep -E "title:"
pause

echo "3. Queue Still Counting next"
call addToUpNext "{\"episodeUuid\":\"$QUEUE_UUID\",\"position\":\"NEXT\"}" | grep -E "title:|positionInQueue"
pause

echo "4. Stop playing in 45 minutes"
call setSleepTimer '{"minutes":45}'
