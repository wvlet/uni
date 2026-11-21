// The docs show the newest uni release, which is always on the wvlet Maven repository
// (https://repo.wvlet.org/maven2). Maven Central gets only some releases (see the installation
// guide), so its newest version is fetched too, for the advice to library authors.
export const WVLET_METADATA_URL =
  'https://repo.wvlet.org/maven2/org/wvlet/uni/uni_3/maven-metadata.xml'
export const CENTRAL_METADATA_URL =
  'https://repo1.maven.org/maven2/org/wvlet/uni/uni_3/maven-metadata.xml'

export async function fetchLatestVersion(
  label: string,
  metadataUrl: string,
  fallback: string
): Promise<string> {
  try {
    const res = await fetch(metadataUrl, {
      headers: { 'User-Agent': 'wvlet-uni-docs' },
      signal: AbortSignal.timeout(3000)
    })
    if (!res.ok) {
      console.warn(`[docs] ${label} returned ${res.status}; using fallback version ${fallback}`)
      return fallback
    }
    const xml = await res.text()
    const version = xml.match(/<release>\s*([^<\s]+)\s*<\/release>/)?.[1]
    if (!version) {
      console.warn(`[docs] No <release> in ${label} metadata; using fallback version ${fallback}`)
      return fallback
    }
    console.log(`[docs] Using latest uni version on ${label}: ${version}`)
    return version
  } catch (err) {
    console.warn(
      `[docs] Failed to fetch latest version from ${label} (${(err as Error).message}); using fallback ${fallback}`
    )
    return fallback
  }
}
