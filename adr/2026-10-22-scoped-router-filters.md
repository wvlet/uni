# Router filters are scoped per route

## Context

`Router` held a single `filterSurfaceOpt`. `Router.andThen` kept one side's filter for all combined routes and `FilterBuilder.andThen` replaced any filter the inner router had, so `Router.of[Public].andThen(Router.filter[Auth].andThen(Router.of[Protected]))` put `/public` behind `Auth`, and an inner filter under an outer one was dropped. Airframe's `RxRouter.filter[F].andThen(...)` scoped filters to their subtree; td-llm-proxy's migration depends on that.

## Decision

- Each `Route` carries its filter chain, `filterSurfaces: Seq[Surface]` (outermost first, default empty). `FilterBuilder.andThen` prepends its filter to every route of the router it wraps; `Router.andThen` only concatenates routes. Filters live on routes, not in a router tree, so `RouteMatcher`, OpenAPI, and other `routes` consumers are unchanged.
- `RouterHandler` runs the filters shared by every route (`Router.commonFilters`, the longest common prefix of the chains) before route matching, then the matched route's remaining filters. So a router-wide filter keeps its old behavior: it sees unmatched requests (CORS preflight, 401 on unknown paths) and can rewrite the request before matching. Narrower filters run only after their route is selected; they cannot reroute the request.
- Filters are still created through `ControllerProvider.getFilter`, lazily and once per filter `Surface`.
- A filter's synchronous exception (including a failing `getFilter`) becomes a failed `Rx`, so outer filters can `recover` from it.
- API: `Router` is now `Router(routes)`. The `Router(routes, filterSurface)` factory and two-argument constructor stay and wrap all routes. `Router.filterSurfaceOpt` is deprecated and returns the outermost shared filter, so a backend that reads it does not silently lose a router-wide filter, but it misses narrower ones; backends must use `Route.filterSurfaces`.

Rejected alternative: a filter-node tree like Airframe's `RxRouter`. It would change how every consumer walks routes, for no gain over per-route chains.

## Consequences

- `RouterHandler` (uni-netty) is the only backend that applies `Router` filters. Node.js and RPC handlers take a plain `RxHttpHandler` / `RPCRouter`, which have no router filters.
- A pattern match on `Route(...)` with six fields no longer compiles.
- When no filter covers every route, unmatched requests are not filtered.
