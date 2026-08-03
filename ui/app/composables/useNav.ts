import { Inbox, AtSign, Globe, KeyRound, Settings2, type LucideIcon } from 'lucide-vue-next'
import type { Section } from '@/composables/useZorail'

export type NavItem = {
  key: Section
  label: string
  icon: LucideIcon
  path: string
}

/**
 * The primary destinations, shared by the desktop sidebar and the mobile bottom
 * bar so the two can never drift apart.
 */
export const NAV: NavItem[] = [
  { key: 'inboxes', label: 'Inboxes', icon: Inbox, path: '/' },
  { key: 'addresses', label: 'Addresses', icon: AtSign, path: '/addresses' },
  { key: 'domains', label: 'Domains', icon: Globe, path: '/domains' },
  { key: 'keys', label: 'API keys', icon: KeyRound, path: '/keys' },
  { key: 'settings', label: 'Settings', icon: Settings2, path: '/settings' },
]

/** Whether `item` is the active destination for the given route path. */
export function isNavActive(item: NavItem, routePath: string): boolean {
  // The inbox detail route (/inbox/…) still highlights Inboxes.
  if (item.key === 'inboxes') return routePath === '/' || routePath.startsWith('/inbox')
  return routePath === item.path
}
