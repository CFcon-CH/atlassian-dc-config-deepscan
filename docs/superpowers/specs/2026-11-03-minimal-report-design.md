# Minimal project and space report

The Jira `projectConfig` and Confluence `spaceConfig` endpoints gain a reduced report mode. It produces one small page per Jira project or Confluence space and a summary table on the selected overview page, carrying only the inventory columns below plus administrator-maintained topic columns. No new endpoint is added. Without the option every existing behavior stays as it is: full report, full detail export, the existing overview, rebuild, export log and bulk export.

## Requested result

Jira, per project: **Project key** (link to `<jira-base-url>/projects/<KEY>/issues`), **Project name**, **Project lead**, **Project administrators**, **Project category**, **Issue count**, **Last issue update**, then the topic columns (**Themengebiet 1**, **Themengebiet 2**, ...).

Confluence, per space: **Space key** (link to `<confluence-base-url>/display/<KEY>/`), **Space name**, **Space description**, **Category**, **Administrators**, **Page count**, **Apps used**, **App macros**, **User macros**, then the topic columns.

The list is generated and refreshed on demand by an administrator (button in the project picker or space inventory). No scheduler is part of this change.

## Request surface

- GET `?report=minimal&format=json|csv|html`. With `project=<KEY>` or `space=<KEY>` one object, without it all objects. A plain read; it never writes.
- POST to the existing write route with `mode: "minimal"`. Same staged lookups (`links`, `spaces`, `pages`), same target selection, same parent rules (`Cx.parentProblem`, picked ID or typed title), same administrator gates and transport.
- The picker and the space inventory get one switch, **Minimal**, in the existing bulk export panel. The bulk loop itself is unchanged: checkbox selection, target stages, sequential writes, progress, stop and **Retry failed**. With the switch on it reads `report=minimal` JSON and posts `mode: "minimal"`.

## Object page

One page per project or space, holding one table with a header row and one data row, laid out as in the requirement screenshots. The page carries its own marker, `cfcon-project-minimal-export/1` or `cfcon-space-minimal-export/1`, and its own default title, `Jira project overview - <KEY>` or `Confluence space overview - <KEY>`. A full configuration page and a minimal page therefore never adopt each other; the existing marker protection refuses the write.

System columns are identified by their header text and rewritten on every export. Every other column is a topic column: its header and its cell are carried over verbatim, so status lozenges, links and formatting survive. Administrators add, rename and fill topic columns in the Confluence editor on the object page.

The read before a write is fail-closed, following the existing Remark carry-over and reusing its helpers (`Cx.TBODY`, `Cx.ROW`, `Cx.cellsOf`, `Cx.headerIndex`, `Cx.hasNestedTableBody`, `Cx.plainText`, `Cx.isEmptyCell`). An existing page without the minimal marker, a missing or duplicated system column, more than one data row, merged cells, a nested table or a duplicated topic header refuses the write. Nothing an administrator entered is ever dropped silently.

## One topic set

All object pages carry the same topic columns. The overview holds the known set. When an export finds a topic column on its page that the overview does not know yet, the column is added to the overview. Every exported page is seeded with every known topic column it lacks, with an empty cell. Matching is by header text, compared after `Cx.plainText` and case-insensitively. A renamed topic header is a new topic; the old overview column keeps its earlier values until those pages are exported again.

## Overview

The overview is a summary of the object pages, maintained through the existing `ConfigOverview` and `OverviewExport.maintain` path: fresh read of the parent body and version, merge, one versioned save, read-back confirmation, outcome `skipped`, `updated`, `failed` or `unknown`.

- It is its own managed region with its own product marker, `jira-minimal` or `confluence-minimal`, so it can sit next to the full configuration overview on the same parent.
- The first column, **Project** or **Space**, links the object name to the object page. Row identity stays as today: row anchor with the page ID, title match with ID check.
- The remaining columns are the object page's columns, including the topic columns. Each column has a stable column anchor; topic columns use the key `topic:<header text>`. Data cells carry values instead of section links.
- Every export rewrites its row from the object page. Edits made directly in the overview are overwritten; the page says so above the table. A change on an object page reaches the overview with the next export of that object.
- A project or space that no longer exists keeps its object page and its overview row. The row is marked as no longer existing; nothing entered by hand is lost. Removing it is an administrator decision.

`ConfigOverview.read` additionally refuses the two minimal markers as an overview, as it does for the full export markers today. Rebuilding overview rows from minimal object pages is out of scope for the first version; `rebuild-overview` keeps its current behavior.

## Data sources

### Jira

| Column | Source |
| --- | --- |
| Key, name, lead, category | `Project` API, as read by the existing **Details** section. |
| Administrators | Actors of the project role **Administrators**: users by display name, groups by group name. Groups are not expanded. |
| Issue count | `IssueManager.getIssueCountForProject(projectId)`, the call already used by `jiraDCappFootprint`. |
| Last issue update | One search `project = <KEY> ORDER BY updated DESC` with one result through `SearchService`, overriding issue security so the value is complete. This is the only new read; the exact calls are checked against the Jira Javadoc before they are written. |

The full report keeps its rule of no issue counting and no search. The minimal mode is the only place that reads issue data, and only these two values.

### Confluence

| Column | Source |
| --- | --- |
| Key, name, description, category | The estate inventory SQL (`SWEEP_SQL`, `SWEEP_CATEGORY_SQL`), extended from counts to the description body and the category label names. |
| Administrators | `SETSPACEPERMISSIONS` grants of the space: users and groups by name. Groups are not expanded. |
| Page count | Current pages, as `COUNT_SQL` in `confluenceDCspaceInfo` (`prevver IS NULL`, current status). |
| Apps used, app macros, user macros | The macro usage scan of `confluenceDCappFootprint`, copied into this endpoint. `appFootprint` does not need to be installed. |

The copied scan keeps its proven parts: the macro catalogue per app, `MacroUsageQuery` over the content index per macro name, the `UserMacroLibrary` read for native user macros, and `includeSystem=false`, so apps provided by the system are not counted as apps. Per space it yields the names of the apps with at least one used macro, the number of distinct app macros used and the number of distinct user macros used. It runs once per run over the whole instance, never once per space, and its result serves every space of that run. Its budget is 120 seconds. A value from a scan that ran out of budget is a lower bound and is marked with `*`. A scan that cannot run is shown as not measured, never as zero.

The copied code is documented as measured on Confluence 10 only. On an older Confluence the three columns are reported as not measured and the rest of the export proceeds.

## Reporting discipline

A failed read is never written as an empty or zero value. Unreadable values are stated in the cell. The page states the generation time and the report version. Property values, the Remark carry-over of the full export and its guards are untouched.

## Verification

Offline: topic carry-over, topic set seeding and growth, editor normalization of the table, fail-closed refusals, marker separation from full pages, overview summary and overwrite, rows of deleted objects, budget-limited macro figures, byte parity of the shared helpers and all existing suites. Live application-link authentication, editor round trips, the issue search and the macro scan on the target instances require an authorized instance test and are not inferred from offline results.

## Alternatives considered

A Page Properties report on the overview would show topic edits without a new export, but its support for the horizontal one-row table of the requirement could not be confirmed from a primary source. A separate endpoint would duplicate transport, gates and target selection. Depending on the installed `appFootprint` endpoint would make the Confluence report incomplete on instances without it.
