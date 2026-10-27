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
| `wvlet.airframe.control.Retry` | `wvlet.uni.control.Retry` |
| `wvlet.airspec.AirSpec` | `wvlet.uni.test.UniTest` |
| AirSpec `initDesign` / `initLocalDesign` | `addDesign` / `addTestDesign` |
| AirSpec `test("...") { (a: A) => ... }` | the same: test arguments come from the suite's Design |

Background: https://wvlet.org/uni/book/appendix-b-airframe.md
