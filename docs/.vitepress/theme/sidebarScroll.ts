import { nextTick } from 'vue'

// VitePress highlights the current page in the sidebar but never scrolls to it, so on a long
// sidebar the highlighted entry can sit below the fold. Scroll only the sidebar, never the
// window, so jumps to a #heading are left alone.
export async function revealActiveSidebarLink(): Promise<void> {
  await nextTick()
  // The active class updates after the route change renders
  requestAnimationFrame(() => {
    const sidebar = document.querySelector<HTMLElement>('.VPSidebar')
    const active = sidebar?.querySelector<HTMLElement>('.VPSidebarItem.is-link.is-active > .item')
    if (!sidebar || !active) return
    const view = sidebar.getBoundingClientRect()
    const item = active.getBoundingClientRect()
    // The top of the sidebar is covered by the nav bar title on wide screens, so leave a margin there
    const topMargin = 64
    if (item.top >= view.top + topMargin && item.bottom <= view.bottom) return
    sidebar.scrollTop += item.top - view.top - sidebar.clientHeight / 3
  })
}
