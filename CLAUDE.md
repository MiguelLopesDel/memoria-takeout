# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Memoria is a local, single-user web app to import and explore a **Google Takeout** export (Brazilian/Portuguese exports specifically — file paths and dates are matched in pt-BR). It ships as one Spring Boot JAR: a Java 21 backend that parses Takeout into SQLite + FTS5, and a React 19 SPA built into the JAR's static resources.

## Commands

Development runs the backend and the Vite dev server as two processes:

```bash
npm install                 # first time only
npm run dev                 # concurrently: `mvn spring-boot:run` + Vite on :5173
# or run them separately:
mvn spring-boot:run         # backend on :8787
npm run dev:web             # Vite on :5173, proxies /api -> :8787
```

Open the Vite dev server at `http://localhost:5173` (it proxies `/api` to the backend). Hitting `:8787` directly only serves a UI if the frontend was built into `src/main/resources/static` first (that only happens in the Docker build, not in `mvn spring-boot:run`).

```bash
npm run check               # tsc --noEmit (frontend type check)
npm run lint                # eslint (max-warnings capped — see Quality gates)
npm run gate                # lint + check (what pre-commit runs)
npm run build               # vite build -> ./dist
mvn -B package              # build target/memoria-0.1.0.jar (runs tests)
mvn -B verify               # FULL backend gate: spotless + checkstyle + tests + CPD
mvn spotless:apply          # auto-fix Java formatting (the canonical answer to any format complaint)
mvn test                    # run all tests
mvn test -Dtest=TakeoutImporterIntegrationTest#importsRepresentativeTakeoutWithoutHtmlFragments   # single test
sudo docker compose up --build     # full prod-style run on :8787 (mount Takeouts via IMPORT_ROOT=/path)
```

## Quality gates & workflow (see docs/rfcs/0001-quality-gates.md)

- **Sensitive-data gate runs first on every commit** (`.githooks/check-sensitive.sh`): forbidden file names (Takeout exports, `.mbox`, `.db`, `.env`, key material), files >5 MB, personal-data markers (`.githooks/sensitive-patterns.txt`: owner e-mail, CPF format) in added lines, and a gitleaks secret scan of the staged index. `pre-push` rescans the ENTIRE history with gitleaks (second barrier — catches `--no-verify` smuggling); CI has a gitleaks job too. It fails closed if gitleaks is missing (binary lives in `~/.local/bin/gitleaks`). Deliberate exception = `ALLOW_SENSITIVE=1 git commit ...`, never `--no-verify`. Note: gitleaks allowlists well-known example keys (e.g. `AKIA...EXAMPLE`), so test it with realistic tokens.
- **Before any commit**: `npm run gate` and (if Java changed) `mvn -B spotless:check checkstyle:check` must pass. Git hooks in `.githooks/` enforce this (`pre-commit` fast gate, `pre-push` full gate incl. `mvn -B verify` + `vite build`); `npm install` activates them via the `prepare` script (`git config core.hooksPath .githooks`). CI (`.github/workflows/ci.yml`) runs the same commands.
- **Limits are a ratchet, never loosen them**: `eslint --max-warnings 7`, Checkstyle `FileLength 2400` / `MethodLength 150` (`config/checkstyle.xml`), CPD duplication ≥200 tokens. If a gate fails, fix the code (modularize, dedupe, `mvn spotless:apply`) — do NOT raise a limit, disable a rule, or add a suppression without the user explicitly deciding that.
- **RFC first for non-trivial changes** (new tab, new parser, schema/`event_key` semantics, new/changed endpoint): write a spec in `docs/rfcs/` from the template, get grilled on it (`grilling` skill) until no open questions remain, then implement with the RFC as the prompt's source of truth. Details in `docs/rfcs/README.md`. If implementation diverges from the RFC, update the RFC in the same PR.
- **Business rules must be understood, not just generated**: when adding a non-obvious conditional or domain rule, surface it to the user (a short "regra que estou assumindo" note or a grilling question) instead of burying it in the diff.

## Configuration (env vars, see `application.properties`)

- `MEMORIA_DATA_DIR` — where `memoria.db` (SQLite) lives. Default `.memoria`.
- `MEMORIA_DEFAULT_TAKEOUT` — path imported when the UI sends no path. Default `/app/Takeout`.
- `MEMORIA_IMPORT_ROOTS` — comma-separated dirs the file browser and relative import paths are confined to (path-traversal guard).
- `MEMORIA_TIMEZONE` — IANA zone (default `America/Sao_Paulo`) used to derive each event's local day/hour/weekday/month at import time. All time-based aggregation groups on those precomputed local columns, not on the UTC `timestamp`.

## Architecture

**Backend flow** (all under `src/main/java/app/memoria/`):

1. `ApiController` — one flat REST controller. Every list/metrics endpoint takes the same 7 filter query params (`q, source, type, domain, from, to, product`) and funnels them through `FilterParams.of(...)`. It delegates SQL work to the focused database modules below and also does CSV/PDF/JSON export (PDFBox). The "Organizar" tab curates tags, collections, notes and saved filters through `AnnotationsService`: `EntityPanel` toggles tag chips (`/api/tags/apply` + `/api/tags/remove`, current tags via `/api/events/tags`), adds to collections, and edits an inline note; `OrganizePanel` browses a tag's or collection's events (`/api/events/by-tag`, `/api/collections/events`).
2. `ImportJobService` — runs imports on a **single-thread executor**, exactly one job at a time. State is held in an `AtomicReference<ImportJob>` and mirrored to the DB so the frontend can poll `/api/import/status`. On startup, a job left in `running` (backend was killed mid-import) is marked `error`.
3. `TakeoutImporter` — the parsing core. Resolves a folder or archive (`.zip/.tgz/.tar.gz/.tar`, extracted to a temp dir), walks it deterministically, and dispatches per-file to format-specific parsers (My Activity JSON/HTML, Chrome history, Gmail `.mbox`, Maps JSON, Calendar ICS, access log, Meet, Google Pay/Play Store, Tasks, NotebookLM, Blogger, and YouTube history/comments/live-chats/posts/messages/uploads). Emits `EventRecord`s through a callback sink, batched at 10k inserts. Everything normalizes into one `events` shape: `timestamp, source, type, title, text, url, domain`.
4. The database is split into four modules: `EventStore` owns schema, migrations and event mutations; `AnalyticsService` owns general reports and event search; `YouTubeService` owns the YouTube deep dive; and `AnnotationsService` owns topics, tags, collections, notes and saved filters. The package-private `EventQueries` shares the filter DSL and common read-side queries between the analytics and YouTube modules. SQLite WAL and tuning PRAGMAs are configured by `EventStore` in `@PostConstruct`.
5. `FileBrowserService` — backs the in-UI folder picker, sandboxed to `MEMORIA_IMPORT_ROOTS`.
6. `SpaController` — forwards `/` to the bundled `index.html`.

**Frontend**: the entire UI is one file, `src/main.tsx`, rendered by Vite. It talks only to `/api/*`. `src/styles.css` is hand-written (no Tailwind here). Radix UI + Recharts + TanStack Virtual are the only UI libs. Tabs: explore / **memórias** / **padrões** / **youtube** / **assuntos** / timeline / calendar / site / organize / export. All `fetch` bodies go through the defensive `readJson` helper (an empty/non-JSON body — e.g. a 404 for an endpoint a stale backend lacks — returns `null` instead of throwing "Unexpected end of JSON input").

**Padrões (aba "Padrões")**: one aggregator `GET /api/patterns` (7 filters + `limit`) → `AnalyticsService.patterns` bundles five analyses, all honoring the shared filters: `rhythm` (7×24 weekday×hour matrix), `streaks` (longest consecutive-active-day runs, computed in Java over `DISTINCT local_day`), `phases` (top `root_domain`s with monthly series + peak month, so a burst reads as "jul–set/2023: fase Wikipedia"), `returns` (reuses `topReturns` globally — pages revisited on the most distinct days), and `searches` (`type='search'` titles grouped case-insensitively, `HAVING count >= 2`).

**Recall (aba "Memórias")**: `GET /api/on-this-day?date=MM-DD` (`AnalyticsService.onThisDay`, default = today in the configured zone) groups events sharing a month-day across years (`substr(local_day,6,5)`), each year with a per-year sample (`ROW_NUMBER() OVER (PARTITION BY year ...)`) plus a random `flashback` from >1 year ago. `GET /api/day?date=YYYY-MM-DD` (`AnalyticsService.day`) returns one local day's events chronologically (capped 5000) for the slide-in `DayPanel`, opened by clicking a Calendar heatmap cell or a Timeline bar, the "Ver um dia específico" date input in the Memórias header, or the "Primeira/Última vez" cards in the YouTube video detail (the summary carries `firstDay`/`lastDay` local days for that). Both use the lean `RECALL_COLUMNS` projection (no `raw_json`).

**YouTube (aba "YouTube")**: ecosystem deep-dive scoped by `source='YouTube' OR root_domain='youtube.com'`, so Takeout activity and Chrome visits to the same watch URL count together. `GET /api/youtube` (`YouTubeService.youtubeReport`) bundles summary/timeline/topVideos/topChannels/`channelPhases` (per-channel monthly series + an ativo/abandonado/novo status anchored on the **last month of data**, not today; the list mixes in the biggest channels whose activity stopped 6+ months before the end, otherwise only long-running channels would surface). `GET /api/youtube/videos` searches by title/channel grouping on the 11-char id lifted from `watch?v=` via SQL `substr/instr` (no extra column); ads rows (`raw_json LIKE '%From Google Ads%'`) are excluded from video rankings, and video titles prefer non-synthetic ones (comments only carry "Comentário em vídeo <id>"). **Counting semantics** (`VIEW_COUNT`): "vezes" = non-interaction events deduplicated by second-truncated timestamp (JSON exports carry milliseconds, HTML doesn't — string equality would double-count); comments/chats/messages/posts are reported separately as `interactions` (a livestream with 200 of your chat messages is 1 view, not 200); the UI labels the count "assistido/aberto". Second truncation is safe here: it only merges rows of the *same video* inside one second, and same-source sub-second twins in real data are logging artifacts milliseconds apart (redirect/re-log), not distinct views. The UI shows thumbnails straight from `i.ytimg.com/vi/<id>/mqdefault.jpg` (no API key; hidden in privacy mode). `GET /api/youtube/video?id=` = first/last time, per-year, per-type and every encounter; `GET /api/youtube/channel?name=` = monthly series + the channel's top videos. Channel names live in the `events.channel` column: JSON-format rows get the exact `subtitles[0].name` (`YouTubeChannels.fromSubtitles`), HTML-format rows use the heuristic `YouTubeChannels.extract` (text between the known title and the pt-BR date); misses are ads with no channel in the export. `EventStore.backfillYouTubeChannels` runs when the column is first added, after every import (`finishImport`) and on demand via `POST /api/backfill/channels`, inside one transaction.

**HTML vs JSON Takeout formats.** Old Takeouts export My Activity as HTML, newer ones as JSON — the same record then exists twice with different `raw_json`, hence different `event_key`s (identity hashes the raw record only, so a re-import after parser fixes updates titles/fields in place without duplicating). Two consequences: (1) JSON titles arrive prefixed with the action verb ("Watched X", "Searched for X") — `parseMyActivity`/`parseYouTubeHistory` strip it via `stripActivityAction` (type inference runs on the raw title first, it relies on those verbs); (2) `POST /api/cleanup/format-duplicates` (UI: "Remover duplicados" na aba Calendário) deletes the HTML row when a non-HTML twin exists with the same source/type/url/second, consolidating annotations onto the surviving row and rebuilding FTS. Byte-identical records across different Takeouts (both JSON) merge automatically through `event_key` — no cleanup needed.

**Assuntos (aba "Assuntos")**: user-defined interest topics (table `topics`: name, color, keywords). Keywords become an FTS5 OR query of quoted phrase-prefixes (`topicFtsQuery`: `"one piece"* OR "manga"*` — unicode61 folds diacritics, so `manga` matches `mangás`). `GET /api/topics/report?keywords=` (also used to preview unsaved topics) honors the 7 shared filters and returns summary, monthly timeline, bySource/byType, topDomains, topPages, topChannels, searches and a recent sample. CRUD: `GET/POST /api/topics`, `POST /api/topics/update`, `POST /api/topics/delete`. The UI ships starter suggestions (Mangá & Manhwa, Anime, Games...) and a "Testar sem salvar" preview mode.

## Things that will bite you

- **Import merges (incremental), keyed by a stable `event_key`.** Re-importing inserts new exported records and updates parser-derived fields in existing rows via `ON CONFLICT(event_key) DO UPDATE`; IDs and their tags/notes/collections survive parser fixes. `event_key` = a reliable natural key when the source has one in `raw_json` (Chrome `time_usec`, YouTube comment/chat/post IDs), else a hash of immutable `raw_json` plus an occurrence ordinal scoped to one file. Scoping preserves byte-identical occurrences within a file while merging the same exported record found in overlapping files (for example My Activity/YouTube and YouTube history). `EventKeys` is shared by importer and startup backfill. Legacy identities are detected and migrated on startup before the UNIQUE index is recreated; annotations are consolidated onto canonical rows before duplicates are removed. **Backfill must wrap its updates in one transaction** (`transactions.executeWithoutResult`), or ~500k autocommit UPDATEs make startup take many minutes.
- **FTS5 is external-content with NO sync triggers.** `events_fts` uses `content='events'`. The `events_ai`/`events_ad` triggers are deliberately dropped. `EventStore` owns every write to `events`: ad-hoc mutations use its transactional mutation funnel, and `mergeImport` rebuilds the index in `finally` on success or failure. New event mutations belong in `EventStore`; read-side modules must remain read-only.
- **Search is a mini query DSL**, parsed in `EventQueries.parseQuery`. The `q` param supports `site:`, `type:`, `source:`, `before:`, `after:`, `path:`, `title:`, `text:` prefixes, the flags `has:url` / `has:time` / `missing:time`, and `-word` to exclude. `source`/`type`/`domain` filter params themselves accept comma-separated values and `-value` negation (`addMultiFilter`). Bare words become an FTS `MATCH`.
- **Localized parsing.** File matching (`isRelevantFile`/`parseFile`/`sourceName`) accepts both pt-BR and English Takeout paths (e.g. `minhaatividade.html`/`myactivity.html`, `chrome/histórico.json`/`chrome/history.json`, `youtube e youtube music`/`youtube and youtube music`). Dates are parsed as ISO-8601 or via the `PT_BR_DATE` regex (`"19 de março de 2025, 02:46:47 BRT"`); zone abbreviations resolve through `brazilOffset` (BRT=-03:00, BRST=-02:00, UTC/GMT=0). Non-ISO/non-pt-BR timestamps become `NULL`.
- **Local time is precomputed on persistence, not in queries.** `events` carries `local_day`, `local_hour`, `local_weekday` (0=Sun) and a local `year_month`; `EventStore` derives all four from `EventRecord.timestamp` via the configured zone and recomputes them atomically whenever a backfill changes the timestamp. `days`/`calendar`/`hourly`/`weekdays`/`timeline` and the `from`/`to` filter all use these columns. Old databases are migrated on startup (`ensureColumn` + a one-time UTC-3 approximation); a full re-import recomputes them exactly.
- **Per-site drill-down**: `/api/site` (`AnalyticsService.siteReport`) returns focused stats for a single `domain` (summary, monthly timeline, type breakdown, hourly, top pages), honoring the same filters so a date range recalculates. The frontend "Site" tab consumes it; the domain filter is single-select so it pairs with this view.
- Import limits are hard caps in `TakeoutImporter`: `HTML_EVENT_LIMIT=200k`, `MAPS_EVENT_LIMIT=100k` per file, `INSERT_BATCH_SIZE=10k`. Drive and Google Photos subtrees are skipped entirely.

- **`last_insert_rowid()` is unsafe across the pooled connection.** An `INSERT` then a separate `SELECT ... WHERE id = last_insert_rowid()` can land on different pool connections and return 0 → empty result → 500. Inserts that need to return the new row use SQLite `INSERT ... RETURNING *` in a single `jdbc.queryForMap` (see `createCollection`, `saveFilter`). `createTag` is safe because it re-selects by the unique `name`.

## Conventions

- Respond to the user in Portuguese; write code comments in English.
- No Co-Authored-By trailers in commits.
- Java 21 (records, switch expressions, text blocks are used throughout). Backend has no service-layer interfaces — services are concrete `@Service` classes.
