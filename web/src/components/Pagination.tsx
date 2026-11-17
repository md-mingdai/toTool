import { CaretLeft, CaretRight } from '@phosphor-icons/react'

interface PaginationProps {
  page: number
  pageSize: number
  total: number
  onChange: (page: number) => void
}

function pageItems(current: number, totalPages: number): Array<number | 'ellipsis'> {
  if (totalPages <= 9) {
    return Array.from({ length: totalPages }, (_, index) => index + 1)
  }
  const items: Array<number | 'ellipsis'> = [1]
  if (current > 4) items.push('ellipsis')
  const start = Math.max(2, current - 2)
  const end = Math.min(totalPages - 1, current + 2)
  for (let page = start; page <= end; page += 1) items.push(page)
  if (current < totalPages - 3) items.push('ellipsis')
  items.push(totalPages)
  return items
}

export function Pagination({ page, pageSize, total, onChange }: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  if (totalPages <= 1) return null

  return (
    <nav className="pagination" aria-label="文件分页">
      <button
        type="button"
        className="pagination-arrow"
        onClick={() => onChange(page - 1)}
        disabled={page <= 1}
        aria-label="上一页"
      >
        <CaretLeft size={15} weight="regular" />
        上一页
      </button>
      <div className="pagination-pages">
        {pageItems(page, totalPages).map((item, index) => (
          item === 'ellipsis' ? (
            <span className="pagination-ellipsis" key={`ellipsis-${index}`} aria-hidden="true">…</span>
          ) : (
            <button
              type="button"
              className={`pagination-page${item === page ? ' is-active' : ''}`}
              key={item}
              onClick={() => onChange(item)}
              aria-label={`第 ${item} 页`}
              aria-current={item === page ? 'page' : undefined}
            >
              {item}
            </button>
          )
        ))}
      </div>
      <button
        type="button"
        className="pagination-arrow"
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages}
        aria-label="下一页"
      >
        下一页
        <CaretRight size={15} weight="regular" />
      </button>
    </nav>
  )
}
