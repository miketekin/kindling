# How it was built

This page goes with the [README](readme.md). It covers how the five log
viewer features were built: the way of working, the rules the code had to
follow, what the code was modeled on, the decisions, and the testing.

## The phases


Each phase is there to avoid or soften a failure that AI-assisted coding is
prone to.

| # | Phase | What happens | Guards against |
|---|---|---|---|
| 1 | Interview | Before any code, the AI asked me questions to settle the goal and the main choices. My answers were written down. | Building the wrong thing because the goal was assumed |
| 2 | Reconnaissance | The AI read the existing code without changing it, and wrote up what it found in a file, with the file and line for each claim. | The reading being skipped or cut short: findings that must be written down and shown to me have to be done. Also, what was learned being lost when a long conversation is shortened. |
| 3 | Plan | A written plan: what would be built, which existing code each new piece would be modeled on, what was being assumed, and how the result would be checked. I approved it, or sent it back, before any code was written. | Code written in the AI's own style and not the project's. Guesses treated as facts. The AI running ahead. |
| 4 | Implementation | On the feature's own branch, and kept to the feature alone. | Changes to code that nobody asked to have changed |
| 5 | Build and scripted run | The full build and tests. Where practical, the AI also drove the running app with a script. | Code that passes its tests but does not work in the running app |
| 6 | Comment check | The new code compared against how the existing code is commented. | Over-commenting. An early audit found about five times as many comments as the project's own. |
| 7 | My testing | I ran each feature by hand. What I found went back as a revised plan, and the cycle repeated. The metrics stripe went through four revisions. | Tests that pass while the feature is wrong. Test data the AI generates carries the same assumptions as the code it wrote. |
| 8 | Commit | One commit per feature, and only after I signed off. | A history cluttered with corrections, in branches meant to be reviewed |
| – | Notes, kept throughout | Written as the work went, not at the end: what the reading found, the plan, each assumption as it arose, the reason behind each decision, and anything put off. | The AI losing what it had learned when a long conversation is shortened. The reasons being lost, since the code is kept sparsely commented. Deferred work being forgotten. |

### How it is kept in force


The way of working is not repeated from memory in each conversation. It is
written in files the AI reads, to keep it reliably on track. What the work
leaves behind goes into files as well:


| File | What it holds |
|---|---|
| `CLAUDE.md` | The standing rules. Loaded at the start of every session. |
| `workflow.md` | The phases above, written so that a session with no history can follow them |
| `comment-style.md` | How upstream comments its code, measured, with the commands to measure it again |
| `recon-*.md` | What the AI's reading of the code found |
| `<feature>-plan.md` | The plan as I approved it |
| `<feature>-decisions.md` | The reasons behind each decision, and the list of assumptions |
| `backlog.md` | Work deliberately put off |

All of these are kept out of the repository so they never appear in a branch
meant for review.


The AI also works inside a container, which was my decision. Of the files on
my computer it can see only this project's folder; keeping it away from
everything else was the main reason. It can reach only a short list of
approved addresses. GitHub is not on the list, so every push is mine.

## Ground rules


The rules the code had to follow:

- Match the surrounding code: its style, its patterns, and how often it
  comments.
- Model every new piece on the closest existing one, and name it.
- No new dependencies.
- No refactoring or reformatting outside the feature.

## What each piece was modeled on


The aim was code that reads as if it had always been there. Wherever Kindling
already did something similar, the new code follows it or uses it directly.

| New piece | Existing code it follows | How |
|---|---|---|
| Drift chart's construction and styling | `Sparkline.kt` | Same shape of function. 19 lines of styling and listener setup are identical. |
| Drift chart's window | The "Popout" action in `MetricCard.kt` | Same `jFrame` helper, same 800 by 600 size |
| Header buttons, and the "Charts" and "Metrics" groups | `clearMarked` and the Marking group in `LogPanel.kt` | Same construction, same layout settings |
| Metrics dropdown | The Marking group's dropdown in `LogPanel.kt` | Same code for its choices and their labels |
| Reading metric names and readings | `MetricsView.kt` | The same two queries, word for word, and the same conversion of each row |
| Recognizing a metrics file | `IdbView.kt` | Same test: is there a `SYSTEM_METRICS` table |
| Finding the CPU and heap metrics by name | `MetricCard.kt` | Same search for "cpu" and "heap", ignoring upper and lower case |
| Telling older metric names from current ones | `MetricTree.kt` | Same test: a capital first letter |
| Opening the database and running queries | `utils/Sql.kt` | Used directly |
| Choosing a file | `MainPanel.kt` and four other places | Same starting folder, and the existing `FileFilter` class |
| The stripe components | `GhostGlassPane` in `DnDTabbedPane.kt` | The same kind of custom-drawn, see-through component |
| Stripe colors for errors, warnings and marks | `MultiThreadView.kt`, `MetricCard.kt`, `LogPanel.kt` | The same theme colors, looked up at the moment of drawing |
| Announcing a change in a stripe's state | `FilterPanel` in `Filtering.kt` | Same listener list, with the helpers in `utils/Swing.kt` |
| Light and dark color sets | `FileFilterSidebar.kt` | Same pair of lists, chosen by whether the theme is dark |
| Color chooser | `TableColorCellEditor.kt` | The same standard chooser |
| Right-click menus | `attachPopupMenu` in `utils/Swing.kt` | Used directly |
| Tooltip that depends on where the pointer is | `ReifiedJXTable.kt` | Same method overridden |
| Loading in the background | `ImagesTab.kt` and others | Same pattern |
| Tests | `WrapperLogParsingTests.kt` | Same test library and form. The subject differs: upstream tests parsing only, and 35 of the 47 new tests check other things. |

What had nothing to follow:

- Matching each log row to the metric reading nearest to it in time
- The drawing of the stripes: how rows are grouped into pixels, and how long
  each bar is
- Catching a click on a swatch before the list handles it
- Moving metric times onto the wrapper log's clock
- Blue and green as theme colors. The theme provides them, but nothing in
  Kindling had used them.

## Some of the decisions I made

These are examples, taken from the notes kept during the work. They are not
the full list.

| Feature | Decision | Why |
|---|---|---|
| Clock drift chart | The chart opens in its own window, not in the sidebar. | A chart needs more room than the sidebar gives, and every sidebar panel is a filter. |
| Clock drift chart | Its button sits in a new "Charts" group in the header. | The header's groups stay sorted by purpose, and a later chart has somewhere to go. |
| Clock drift chart | With no drift warnings in the log, the button stays visible but disabled, with a tooltip saying why. | Hidden, nobody would learn the feature exists. An empty chart would be a dead end. |
| Clock drift chart | Each warning is drawn as a thin stem from zero, not as a point on a line. | Warnings are separate observations. A line would suggest values in between. |
| Marker stripe | The stripe is its own strip beside the scroll bar, not drawn inside it. | Inside, the scroll bar's handle would cover the marks. |
| Marker stripe | Level coloring shows the rate of warnings and errors, not merely where one occurred. | On a busy log nearly every position has one, and the stripe was solid red. |
| Marker stripe | Coloring is off by default, and its switch lives in the Levels panel. | Turning on a color mode takes you to the panel that explains the colors. |
| Logger and thread colors | There are two color modes: by logger and by thread. | To see which logger, or which thread, dominates each part of the log. |
| Logger and thread colors | Colors go in spectrum order by rank: red for the most common, then orange, yellow, green, blue. | The order is easy to remember. |
| Logger and thread colors | Each mode's switch sits in its own panel, next to the swatches. An earlier single header button was dropped. | In use, the header button separated the switch from its legend. |
| Logger and thread colors | The five most common are colored automatically. Any change you make to a color freezes them all. | Useful with no setup, and stable once you start investigating. |
| Logger and thread colors | The standard color chooser, untrimmed. | It matches the chooser the multi-file view already uses. |
| Metrics stripe | A second stripe, not more modes on the first. | Seeing markers and usage at the same time is the point. |
| Metrics stripe | A dropdown with text labels and a folder button, not icon buttons. | The available icons already mean something else in the same view. |
| Metrics stripe | One metric at a time: CPU or memory. | Two lanes side by side would each be too narrow to read. |
| Metrics stripe | The stripe disappears entirely when set to Off. | An empty stripe would take up space for nothing. |
| Metrics stripe | The timezone offset is corrected in the stripe, not in the wrapper log parser. | A parser change would alter the displayed times of every wrapper log from another zone. |
| Metrics stripe | A missing `gateway-info.json` is reported in a tooltip, not a dialog. | Files are re-added often; a prompt each time would get in the way. |
| Usage overlay | The existing drift chart is extended. No new button or window. | Smallest change to the interface. |
| Usage overlay | Opening the chart loads both CPU and memory. | Comparing them with drift is the purpose, so one click should show both. |
| Usage overlay | The time axis covers both the drift events and the metrics. | The two often cover different spans; the longer one should not be cut off. |
| Usage overlay | Two axes: milliseconds on the left, percent on the right. | On a single normalized axis the drift values would be lost. |

## What my testing changed

Each of these was found only when I used the feature by hand:

| Found | Result |
|---|---|
| The drift chart found no events in a wrapper log. The warning is split over two lines there, and the value is on the second. | Extraction reads the continuation line as well. |
| Drift events seconds apart merged into one block, hiding the variation inside an episode. | Stem width now follows the zoom level. |
| Opened from a system log, the drift chart's window had a garbled title. | The title now uses the file's name. |
| The first stripe design marked any position with a warning or error. On a busy log that was solid red. | The stripe shows the rate, not mere presence. |
| Reading the CPU values off the Metrics tab showed they are stored as 0 to 100. The first version had guessed the scale from the peak. | The scale is no longer guessed. Values are always read as 0 to 100. |
| The metrics stripe sat hours away from the matching log rows when the gateway and the viewer were in different timezones. | Samples are remapped using the gateway's timezone. |
| `gateway-info.json` was present but reported as missing. The parser had the layout wrong. | Parser fixed, and "missing" is now reported separately from "unreadable". |

## Tests


Testing was done two ways, by different hands.

**By hand, by me.** I tested every feature in the running app before it was
committed. What that changed is under "What my testing changed".

**Automated, written by Claude Code.** 47 new unit tests, written with
kotest, the test library upstream uses. They cover logic that can be checked
without a screen. The drawing of the two stripes has no unit tests.
