# Minimal project and space report

The Jira `projectConfig` and Confluence `spaceConfig` endpoints have a reduced report mode. It keeps **one managed table on the selected overview page**, one row per Jira project or Confluence space, with the inventory columns below and administrator-maintained topic columns. It writes no page per project or space. No new endpoint is added, and without the option every existing behavior stays as it is: full report, full detail export, the existing overview, rebuild, export log and bulk export.

The first version (0.6) wrote one small page per object and a summary rewritten from those pages. It was replaced in 0.7: the overview table extends itself, so the pages added nothing but a second place to maintain topics.

## Requested result

Jira, per project: **Project key** (link to `<jira-base-url>/projects/<KEY>/issues`), **Projektname**, **Projekt-Lead**, **Projekt-Admins**, **Projekt-Kategorie**, **Anzahl Issues**, **Last Issue Update**, **Verwendete Apps**, **Anzahl App-Felder**, **Anzahl Workflow-Erweiterungen**, then the topic columns.

Confluence, per space: **Bereichskey** (link to `<confluence-base-url>/display/<KEY>/`), **Bereichsname**, **Bereichsbeschreibung**, **Kategorie**, **Administratoren**, **Anzahl Seiten**, **Verwendete Apps**, **Anzahl App-Makros**, **Anzahl User-Makros**, then the topic columns.

The list is generated and refreshed on demand by an administrator. No scheduler is part of this change.

## Request surface

- GET `?report=minimal&format=json|csv|html`. With `project=<KEY>` or `space=<KEY>` one object, without it all objects. A plain read.
- POST `action: "minimal"` on the existing route, with the target (Jira: application link), the space, exactly one of `parentPageId` or `parentTitle`, and `keys`. The server reads the values itself and writes all rows with one save of the overview page.
- A **Minimal report** switch in the bulk export of the project picker or space inventory (all selected keys, one request) and in the export form of a report page (that one key).

## The table

- Its own managed region with `cfcon-minimal-<product>-start` and `-end` anchors, separate from the full overview, so both can sit on one page.
- Every generated heading carries a column anchor (`cfcon-minimal-<product>-col-<id>`); every key cell carries a row anchor with the key (`cfcon-minimal-<product>-row-<base64url key>`).
- An export rewrites the generated cells of the exported rows only. Every other column (topics) and every other row is kept verbatim. A new row gets empty topic cells. Columns may be renamed, moved or added in the editor; generated cells are found by their anchors, not by position.
- A new table starts with **Themengebiet 1** and **Themengebiet 2**.
- Fail-closed read: missing or repeated generated columns, a row without its anchor, duplicate keys, merged cells, nested tables or broken markers refuse the write.
- Rows of keys that no longer exist are marked `NO LONGER EXISTS`, never removed; without a complete key list nothing is marked. Archived Jira projects count as existing.
- Stages as `OverviewExport.maintain`: fresh read with version, merge, one versioned save, read back and confirm the generated cells of every exported row; outcome `updated`, `failed` or `unknown`.
- A typed overview title is resolved after the values are read, so a refused request creates no page. In Confluence the page creation and the save share the export's unit of work and roll back together.

## Data sources

### Jira

| Column | Source |
| --- | --- |
| Key, name, lead, category | `Project` API. |
| Administrators | Actors of the project role **Administrators**, users by display name, groups by name, not expanded. |
| Issue count | `IssueManager.getIssueCountForProject(projectId)`. |
| Last issue update | One search `project = <KEY> ORDER BY updated DESC`, one result, overriding issue security; a search only under the administrator's security is a lower bound (`*`). |
| Apps, app fields, workflow extensions | The attribution rules of `jiraDCappFootprint`, built in. App fields: distinct custom fields on the screens of the project's issue type screen scheme whose type key belongs to a non-system plugin (longest plugin key prefix). Workflow extensions: conditions, validators, pre and post functions in the workflows of the project's workflow scheme owned by a non-system plugin, by `full.module.key`, else `class.name` against workflow module implementation classes, else the module class. Apps: the plugins behind both. Each scheme and workflow is read once per request; a budget of 120 seconds, past it `not measured`. |

### Confluence

| Column | Source |
| --- | --- |
| Key, name, description, category | Set-based statements over SPACES, BODYCONTENT and team labels. |
| Administrators | `SETSPACEPERMISSIONS` grants, users and groups by name. |
| Page count | Current pages, as in `confluenceDCspaceInfo`. |
| Apps, app macros, user macros | The macro usage scan of `confluenceDCappFootprint`, built in and resolved by name; once per request; distinct macros per space; 120-second budget, `*` for a lower bound, `not measured` without a macro usage query. |

## Reporting discipline

A failed read is never written as empty or zero; the cell states it. The full reports keep their rules: the minimal report is the only place projectConfig reads issue data and the only place spaceConfig counts pages or queries the content index for macros.

## Verification

Offline: table creation with default topics, topic and row preservation after edits, renamed, added and moved columns, editor normalization, every fail-closed refusal, coexistence with the full overview and the other product, rows of deleted objects, the update stages, the Jira collectors including the app columns against platform stubs, the Confluence statements and macro scan against stubs, and the minimal action through the actual Confluence export transaction. Live behavior on an instance is not inferred from these tests.
