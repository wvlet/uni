# Airframe → Uni

Uni uses a separate package root (`wvlet.uni.*`), so both libraries can coexist
during a migration.

| Airframe | Uni |
|----------|-----|
| `wvlet.airframe.Design` / `newDesign` | `wvlet.uni.design.Design` / `Design.newDesign` |
| `wvlet.log.LogSupport` | `wvlet.uni.log.LogSupport` |
| `wvlet.airframe.codec.MessageCodec` | `wvlet.uni.weaver.Weaver` (`derives Weaver`) |
| `wvlet.airframe.surface.Surface` | `wvlet.uni.surface.Surface` |
| `wvlet.airframe.rx.Rx` | `wvlet.uni.rx.Rx` |
| `wvlet.airframe.http.Http` | `wvlet.uni.http.Http` |
| `wvlet.airframe.http.netty.Netty.server` | `wvlet.uni.http.netty.Netty.server` (JVM only), or `Http.server` for cross-platform code |
| `wvlet.airframe.http.filter.Cors` (`Cors.Policy`, `Cors.newFilter`) | `wvlet.uni.http.CorsFilter(CorsPolicy())`, added with `Http.server.withFilter` |
| `wvlet.airframe.http.RPCStatus` / `RPCException` | `wvlet.uni.http.ServiceStatus` / `ServiceException`: same status names and codes, shared by RPC, Electron IPC, and MCP |
| `wvlet.airframe.canvas.Canvas` | `wvlet.uni.canvas.Canvas`: `release` is `close()`, values are little-endian on every platform, and off-heap canvases need Java 22+ on the JVM |
| `wvlet.airframe.control.Retry` | `wvlet.uni.control.Retry` |
| airframe-parquet `Parquet.newReader[A]` / `newReader[Map[String, Any]]` / `newReader[Json]` | `wvlet.uni.cloud.parquet.Parquet.readAs[A](path)` / `readRows(path)` / `readJson(path)`, returning a `Seq`, not a reader whose `read()` returns null at the end |
| airframe-parquet `Parquet.newWriter[A](path)` / `newRecordWriter(path, schema)` | `Parquet.write(path, rows)` or `Parquet.newWriter[A](path)` for case classes with `derives Weaver`; `Parquet.newRecordWriter(path, schema)` for maps and JSON |
| airframe-parquet `Parquet.readStatistics(path)` | `Parquet.open(path).columnStatistics` |
| `wvlet.airspec.AirSpec` | `wvlet.uni.test.UniTest` |
| AirSpec `initDesign` / `initLocalDesign` | `addDesign` / `addTestDesign` |
| AirSpec `test("...") { (a: A) => ... }` | the same: test arguments come from the suite's Design |

Background: https://wvlet.org/uni/book/appendix-b-airframe.md
