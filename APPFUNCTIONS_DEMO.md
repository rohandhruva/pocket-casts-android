# Pocket Casts × AppFunctions — Demo One-Pager

**Pitch:** Podcast apps are built for thumbs. We exposed Pocket Casts to on-device agents through Android AppFunctions so an agent can plan and control your listening while your hands are busy.

## The hook prompt
> "I've got a 75-minute drive. Fill it with new episodes I haven't heard, start playing, and stop when I get there."

The agent calls `searchEpisodes`, filters `NOT_PLAYED`, sums `durationSeconds` to fit 75 min, then `playEpisode` → `addToUpNext` → `setSleepTimer(75)`. One sentence, ~5 real app calls, app never opened.

**Fallback prompt (proven end to end):** "Play the latest Radiolab, queue the newest Still Counting after it, and stop playing in 45 minutes."

## Run of show (~3 min)
| Time | Beat | Say / do |
|---|---|---|
| 0:00 | Problem | "Podcast apps are built for thumbs. I listen when my hands are busy: driving, running, cooking." |
| 0:15 | Hook | Drive prompt. Point at the mirrored screen: player starts, Up Next fills, sleep timer appears. |
| 1:00 | Correction | "Actually, put the latest Still Counting next. I want the election update first." (`addToUpNext` NEXT) |
| 1:30 | Bedtime | "I'm going to bed, stop at the end of this episode." (`setSleepTimer` endOfEpisode) |
| 1:50 | Under the hood | `adb shell cmd app_function list-app-functions` — 5 functions, ~300 lines, reuse the app's existing playback and Up Next code. Any Android 16 agent can call them. |
| 2:20 | Credibility | "Android blocks background playback starts, so `playEpisode` verifies playback started and returns a clear error otherwise." |
| 2:40 | Close | Next: catalog search for unfollowed podcasts, episode numbers, "bookmark that" for Plus users. |

## Judging criteria → what they see
- **Agentic integration:** one sentence → multiple real actions inside Pocket Casts.
- **User impact:** hands-free commute / bedtime listening without touching the screen.
- **Creativity:** the agent reasons over app data (durations, played status) to build a time-boxed queue — not possible with a simple "play X" command.

## Functions
| Function | Does |
|---|---|
| `searchEpisodes(query?, podcastTitle?, limit?)` | Newest-first search of followed podcasts; blank = latest across all |
| `playEpisode(episodeUuid)` | Plays now; verifies playback started |
| `addToUpNext(episodeUuid, position = NEXT\|LAST)` | Queues without interrupting |
| `setSleepTimer(minutes \| endOfEpisode)` | Pauses after N min or at episode end |
| `addBookmark(title?)` | Bookmarks current position (needs Plus — skip in demo) |

## Pre-show checklist
- [ ] Open Pocket Casts, play something, pause it (keeps the playback service warm → avoids background-start block)
- [ ] Cancel the leftover sleep timer; clear Up Next so it visibly fills
- [ ] Wi-Fi on; demo episodes are downloaded
- [ ] Mirror the phone (`android device hub` or scrcpy)
- [ ] Rehearse the exact prompt 2–3× on the demo agent
- [ ] Record a backup: `adb shell screenrecord /sdcard/demo.mp4`
- [ ] Live fallback: `scripts/appfunctions_demo.sh`

## Avoid on stage
- Podcasts you don't follow (no catalog search yet)
- Episode numbers ("episode 700") — not supported; also say "Radiolab", not "Radio Lab"
- Bookmarks (account has no Plus)

## Where the code is
- `app/.../appfunctions/PocketCastsAppFunctionService.kt` — functions + KDoc
- `app/.../appfunctions/AppFunctionResults.kt` — result types
- `app/src/main/res/xml/app_functions_metadata.xml` — app description for agents
- `EpisodeDao.searchSubscribedEpisodes` — library search query
- Branch: `hackathon/appfunctions-mvp` (uncommitted)
