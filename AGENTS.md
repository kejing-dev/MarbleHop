# Marble Hop 中国跳棋: project guidance

Chinese checkers for PICO OS 6 Shared Space, PICO Spatial SDK 6.1.9, package `io.embodit.marblehop`, v0.1.2.
Design contract: spatial-app-factory `apps/marble-hop/design-package.json` (v1.3) + `prd.html` + `sample.html`.
Started from the factory board-game exemplar (two containers, theme, board-input template, sound player, debug receiver);
the star board, rules, bots, scene and pages are new. `v2[]` of the design is not built (replay, notation codes, hints,
resign, second skin, per-seat bot mixing, 2-ply bot, super hops, online play).

## Surfaces
- **Board volume** (default container `MarbleBoard`, Volumetric 1.02 x 1.16 x 1.16 m (1.08 before v0.1.2), Fixed scale, gravity aligned,
  no base panel material): Ø 1.00 m walnut slab + beech face + rim, 121 holes (pitch 6.5 cm) tilted about the near
  bottom edge (0 / 30 / 45 / 60 / 75 / 90°, default 75°), 60 glass marbles Ø 4.6 cm in six seat colours, six seat pips
  on the rim, lit holes (blue dot = step, amber ring = hop), select ring, ghost marble, chain dots, red ring, landing
  glow, hop trails, and the status sign (AttachmentPanel, upright beyond the far edge).
- **Control window** (`MarbleControl`, Planar 320 x 640 dp fixed, `Placement.right(board)`): native SpatialUI `TabBar` with
  3 pages Game / Rules / Settings. The new-game setup, hand-over and result live on the Game page. Opened through
  `LevelGate` (D9). Settings → 重开控制窗 closes, waits for ON_DESTROY, reopens beside the board.

## Code
| Path | What |
|---|---|
| `domain/StarBoard.kt` | 121 holes in axial (q, r), camps 0–5, apex, pitch → metres |
| `domain/StarRules.kt` | steps, hop-chain BFS (fewest hops, smallest-index ties), camp rules, win (a = 10 or a ≥ 7 and a + k = 10), ranking |
| `domain/Bot.kt` | easy / smart greedy bots + the naive yardstick bot (tuned: axis-projection score) |
| `domain/Picking.kt` | hit hole → nearest candidate (1.5 pitch), arrow keys (±50°), drag snap (0.5 in / 0.75 out) |
| `domain/GameFlow.kt` | the single state (`PlayState`), phases S00–S20, turns, hot-seat rotate + hand-over, undo, save |
| `domain/BoardGeometry.kt` | metres: tilt, facing, sign, drag projection, D35 volume points |
| `ui/board/*` | SpatialView, `BoardScene` (entities; per-frame motion in `BoardFrameSystem`), resources, `input/` (factory template + `HoleTargets`) |
| `ui/control/*` | the control window pages |
| `platform/*` | Application / graph, SoundPlayer, WindowCtl; debug builds add `DebugCommandReceiver` |

Rules - state - render: the scene only renders `PlayState`; every input goes through `BoardListener` → `GameFlow`.
3D input = factory template `ui/board/input/BoardInput.kt` (D50: controller ray, hand ray, eye-hand; short drags count as
taps; drags summed with DragTotal; log mirrored to files/input-log.txt). Every hole is a collider holder `hole:<i>`
(0.92 x 0.86 pitch, tiling the face) with its visible groove disc `groove:<i>` (system HoverEffect draws there);
marbles are `marble:<hole>`. The hit hole then goes to the nearest candidate within 1.5 pitches (gate B: big targets).

## Build / test (macOS kj-mbp-m5 since v0.1.1; Windows kj-alienware before)
`local.properties` (not in git) needs `sdk.dir=` your Android SDK (macOS: `~/Library/Android/sdk`).
```bash
# macOS
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
# Windows Git Bash: export JAVA_HOME=/c/Users/<you>/.pico/primer-cli/jdk/<jdk-21> JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
./gradlew assembleDebug :app:testDebugUnitTest
python3 tools/build_icon.py --icon512 <factory>/apps/marble-hop/icon.png
python3 tools/loudness.py <factory>/apps/marble-hop/qa/loudness-v<version>.wav
~/.factory-venv/bin/python tools/import_marbles.py    # glass-marble sounds (numpy + scipy; macOS afconvert)
```
`tools/mh.sh` / `tools/shot.sh` find adb via `$ADB`, PATH, or the SDK default on macOS / Windows.

## Debug broadcast (QA screenshots)
`adb -s <serial> shell am broadcast -a io.embodit.marblehop.DEBUG -p io.embodit.marblehop --es cmd <cmd> --es a "<arg>"`
Log tag `MarbleHop`. Holes are named `q,r` (axial). Board commands go through the same listener as real pinches.
Launch the app (monkey) before sending broadcasts; the emulator runs ~1 fps, wait 6–8 s before a screenshot.

| State | Command (preconditions) | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop, launch, capture; `s00hold off` after | plain disc + two balls for 10 s |
| S01 第一次 | `goto S01` | welcome card, 2-player board laid out |
| S02 新局 | `goto S02`; `setup` | players 2/3/4/6 + opponent; change players with the segments (re-lays the board) |
| S03 待走 | `goto S03` (= `reset`) | 2 players vs easy bot, you (mint) first |
| S04 已选珠 | `goto S04` | (2,-5) lifted, lit holes |
| S19 落点预览 | `goto S19` | ghost in (2,-4) |
| S20 拖珠中 | `goto S20` | held over (2,-4), not released |
| S05 蹦跳中 | `goto S05` | triple hop (0,-4)→(0,2), frozen 420 ms in; `freeze off` releases |
| S06 电脑在想 | `goto S06` | amber starts, bot held; `hold off` lets it move |
| S08 提示 | `goto S08` | 「到不了那儿」 held; `alerthold off` releases. Stuck: `reset`, `tap -1,-8`... any boxed-in marble |
| S09 终局 | `goto S09` | mint steps its 10th marble home |
| S11 / S14 设置 | `goto S11`; `tilt 0|30|45|60|75|90` | |
| S12 规则 | `goto S12`; `rules 1` = how to play | |
| S13 确认 | `goto S13` (new-game ask after one move); `exitask`; hot-seat undo: `goto S18` then `undo` | |
| S16 恢复 | force-stop mid game, relaunch (or `goto S16`) | sign 「接着上次」 1.5 s |
| S17 转盘中 | `goto S17` (hot-seat, after your move the board turns 180° in 600 ms) | |
| S18 交接 | `goto S18`, wait 1 s | 「轮到琥珀 · 点开始」; `start` |
Modifiers: `lang zh|en|system`, `sound off|1|2|3`, `mute on|off`, `new "players=6 opp=hotseat first=0"`, `page …`,
`nav up|down|left|right`, `drop`, `undo`, `newgame`, `confirm`, `cancel`, `replay "q,r hand-ray|eye-hand|controller"`,
`dump`, `evidence`.

## Known
- Cold-start view (QA #2 q2-1, fixed in v0.1.2): Shared Space centres the VOLUME, and `Placement.right` hangs the window off
  its right face (offset can't be negative). 560 dp reached ~+45–48°. Now: volume 1.02 m wide (board Ø 1.00 unchanged,
  1 cm side margin) + window 320 x 640 dp (320 dp is the narrowest Planar window: asking 280 still opened 320, logcat
  `size(640, 1800)`). Measured right edge 38.1° (±39° view), saved-game and cleared-data starts alike.
  `domain/ControlPlacement.kt` + `ControlPlacementTest` hold the model; keep every page inside 320 dp (one column).
- Not verified on a headset: controller ray / eye-hand / hand ray on the 121 holes, drag feel, system pointer
  visibility, window handle drag, the volume really opening at 1.02 x 1.16 x 1.16 m (N15).
- Sounds are SoundPool (not spatial). Glass-marble recordings since v0.1.1. Sources: factory apps/marble-hop/build/sound-sources.md.
