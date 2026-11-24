# Feature Uni's cloud data components in the docs

## Goals

- Make Uni's data-lake stack visible: object storage (S3, R2, MinIO, GCS, Azure Blob), Parquet, Apache Iceberg, Delta Lake, and Arrow IPC.
- Today a visitor can't tell from the home page, site description, or sidebar that Uni reads and writes Iceberg tables.
- Match the docs layout to the code: PR #1161 and #1164 moved these APIs under `wvlet.uni.cloud.*`.

## Background

- Iceberg and Delta are subsections of the 1,317-line `docs/text/parquet.md`; nothing in the sidebar points to them.
- The sidebar puts Parquet and Arrow under "Data & Serialization" and Object Storage under "Files & Memory".
- What sets Uni apart: pure Scala with no Hadoop, iceberg-java, or cloud SDKs. Platform scope differs: Parquet and object stores run on the JVM, Scala.js (browsers via async I/O), and Scala Native; Iceberg and Delta use sync I/O only, so JVM, Native, and Node.js.
- Real limits that the pages must state: Iceberg writes only Hadoop-style tables (no catalog commits), identity partitions only, Delta is read-only.
- Old URLs are referenced from outside: the published npm package's homepage and README point to `/text/parquet#from-typescript`; the skill points to `/core/object-storage.md`.
- `docSnippets` compiles the fences of `docs/text/parquet.md` and `docs/core/object-storage.md`; the moved pages must stay covered.

## Design

Lane: Express — docs-only, no change to program logic or any always-gate area.

- New sidebar group **Cloud Data**: Overview, Object Storage, Parquet, Iceberg Tables, Delta Lake Tables, Arrow IPC.
- Pages move to `docs/cloud/` (`storage.md`, `parquet.md`, `iceberg.md`, `delta.md`, `arrow.md`, `index.md`), matching the package names.
- `docs/cloud/index.md` is a short overview: what each piece does, one end-to-end example (write an Iceberg table to S3, read it back with a filter), and when to reach for Spark, Trino, DuckDB, or iceberg-java instead, framed as engines that work alongside Uni.
- The Parquet page keeps files, datasets, filters, writing, and TypeScript; the Iceberg and Delta sections move whole into their own pages, with links both ways.
- Old HTML URLs keep working: redirect pages `docs/public/text/parquet.html`, `text/arrow.html`, `core/object-storage.html` with absolute `/uni/cloud/...` targets. A small anchor map sends moved Iceberg/Delta anchors to their new page; other anchors carry over.
- Old `.md` copies (for agents) will 404; every in-repo link moves to the new paths, including `uni-npm` package.json and README (the redirect covers the already-published package).
- Each page and the home card state platform scope per component (docs/AGENTS.md "Call out platform scope").
- Home page: one feature card "Cloud Data" linking to `/cloud/`; the site description, README, `guide/index.md`, and `modules.md` mention Parquet and Iceberg on object storage.
- Wording says "cloud data" or "data lake tables", never "cloud-native".
- AI-agent docs: split the single huge Parquet row of `for-ai-agents.md` into Parquet, Datasets, Iceberg, and Delta rows; update URLs in the `uni` skill, add an Iceberg section to its references if missing, and bump the plugin version.
- `docSnippets` gets one root `docs/cloud -> snippets.cloud`, so the new pages (arrow, iceberg, delta, overview) compile too; fences that can't compile get `skip`. `./sbt docSnippets/test` and `pnpm docs:build` validate.
- The sidebar entries leave the shared "Data & Serialization" and "Files & Memory" groups, so llms.txt lists the new section (`/cloud/llms.txt` is generated).

## Alternatives and Why Not?

- **Keep pages where they are and only add a sidebar group.** Leaves Iceberg buried in the Parquet page and the paths out of step with `wvlet.uni.cloud`.
- **Move without redirects.** Breaks the published npm package's homepage link and external bookmarks.
