const GITHUB_RELEASES_URL =
  'https://api.github.com/repos/wvlet/uni/releases/latest'

export async function fetchLatestVersion(fallback: string): Promise<string> {
  try {
    const res = await fetch(GITHUB_RELEASES_URL, {
      headers: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'wvlet-uni-docs'
      },
      signal: AbortSignal.timeout(3000)
    })
    if (!res.ok) {
      console.warn(
        `[docs] GitHub releases API returned ${res.status}; using fallback version ${fallback}`
      )
      return fallback
    }
    const data = (await res.json()) as { tag_name?: string }
    const tag = (data.tag_name ?? '').trim().replace(/^v/, '')
    if (!tag) {
      console.warn(`[docs] Empty tag from GitHub; using fallback version ${fallback}`)
      return fallback
    }
    console.log(`[docs] Using latest uni version: ${tag}`)
    return tag
  } catch (err) {
    console.warn(
      `[docs] Failed to fetch latest version (${(err as Error).message}); using fallback ${fallback}`
    )
    return fallback
  }
}
