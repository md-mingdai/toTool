import {
  AppWindow,
  ArrowsLeftRight,
  ClipboardText,
  DownloadSimple,
  FileArchive,
  FileText,
  FilmStrip,
  HardDrives,
  Images,
  MusicNotes,
  Package,
} from '@phosphor-icons/react'
import type { CategoryGlyph } from '../types'

export function CategoryIcon({ name, size = 18 }: { name: CategoryGlyph; size?: number }) {
  switch (name) {
    case 'storage':
      return <HardDrives size={size} weight="regular" />
    case 'photos':
      return <Images size={size} weight="regular" />
    case 'videos':
      return <FilmStrip size={size} weight="regular" />
    case 'audio':
      return <MusicNotes size={size} weight="regular" />
    case 'documents':
      return <FileText size={size} weight="regular" />
    case 'archives':
      return <FileArchive size={size} weight="regular" />
    case 'apks':
      return <Package size={size} weight="regular" />
    case 'apps':
      return <AppWindow size={size} weight="regular" />
    case 'downloads':
      return <DownloadSimple size={size} weight="regular" />
    case 'clipboard':
      return <ClipboardText size={size} weight="regular" />
    case 'transfers':
      return <ArrowsLeftRight size={size} weight="regular" />
    default:
      return <FileText size={size} weight="regular" />
  }
}
