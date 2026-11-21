# Gallery: a Get started page

## Goals

- People who land on the UI gallery and want to use Uni can set up a Scala.js project without leaving it.
- The page is a quick start: build files, CSS, imports, a minimal app shown live, and how to run it.

## Background

- The gallery routes and sidebar list `ComponentPage`s (examples + API + a11y checks) and `GuidePage`s (a page with its own view, such as Colors).
- The docs insert the newest release with `__UNI_VERSION__`, read from repo.wvlet.org's `maven-metadata.xml` at docs build time.
- repo.wvlet.org sends no CORS headers, so the gallery cannot fetch that file from the browser.
- Every release tag goes to repo.wvlet.org; Maven Central gets only some releases, so the snippet includes the resolver.
- The docs workflow builds the gallery from main with full git history, so sbt-dynver knows the last release tag.

## Design

- Lane: Standard, but the launch prompt carries the full direction and the merge condition (quick-review fixes), so it proceeds without a design card.
- A "Get started" group sits first in the sidebar and rail, with one page at `#/start`; a URL without a hash opens it.
- The page is a `GuidePage` (the page type the Colors page added), so routing, the tree, and the command palette work unchanged; its view, `StartView`, renders the guide sections.
- Sections: 1 build files (`project/build.properties`, `project/plugins.sbt`, `build.sbt`), 2 loading uni-ui.css, 3 imports, 4 a minimal app shown live in the existing example card plus `index.html`, 5 running it (`~fastLinkJS` + a static server, or Vite) and where to go next.
- Version: generated at build time from the build's last release tag (`previousStableVersion` when the build is a snapshot), plus the build's Scala, Scala.js, and sbt versions; a link to the GitHub releases page sits next to it. No runtime fetch, since CORS blocks it.
- The build snippet writes the linker output into `web/js`, so `index.html` and a plain static server work without a bundler.
- Static code blocks share a copy button extracted from the code panel.
- The snippets are checked by building a scratch project against the published release.
- `docs/dom/index.md` Setup links to the page.

## Alternatives and Why Not?

- Fetch the latest version at runtime: blocked by CORS on repo.wvlet.org.
- Use `version.value` directly: on main it is a `-SNAPSHOT` that users cannot resolve.
- A `guide` flag on `ComponentPage`: the first draft did this, but main gained `GuidePage` meanwhile, so one mechanism is enough.
