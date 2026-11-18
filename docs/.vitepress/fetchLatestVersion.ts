// The docs show the newest uni version on Maven Central, so every snippet works without an extra
// resolver. Newer versions may exist only on https://repo.wvlet.org/maven2 (see the installation guide).
const CENTRAL_METADATA_URL =
  'https://repo1.maven.org/maven2/org/wvlet/uni/uni_3/maven-metadata.xml'

export async function fetchLatestVersion(fallback: string): Promise<string> {
  try {
    const res = await fetch(CENTRAL_METADATA_URL, {
      headers: { 'User-Agent': 'wvlet-uni-docs' },
      signal: AbortSignal.timeout(3000)
    })
    if (!res.ok) {
      console.warn(
        `[docs] Maven Central returned ${res.status}; using fallback version ${fallback}`
      )
      return fallback
    }
    const xml = await res.text()
    const version = xml.match(/<release>\s*([^<\s]+)\s*<\/release>/)?.[1]
    if (!version) {
      console.warn(`[docs] No <release> in Maven Central metadata; using fallback version ${fallback}`)
      return fallback
    }
    console.log(`[docs] Using latest uni version on Maven Central: ${version}`)
    return version
  } catch (err) {
    console.warn(
      `[docs] Failed to fetch latest version (${(err as Error).message}); using fallback ${fallback}`
    )
    return fallback
  }
}
