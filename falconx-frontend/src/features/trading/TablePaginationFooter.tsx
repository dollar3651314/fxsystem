interface Props {
  page: number;
  total: number;
  pageSize: number;
  /** 设为 true 时分页按钮 disabled（避免 fetch 中重复点）。 */
  fetching?: boolean;
  onChange: (next: number) => void;
}

/**
 * Activity 各 tab 表格底部的分页栏。统一文案 / 样式，避免每个 table 各写一份。
 * 总是渲染（即便 totalPages=1）—— 让用户始终能看到「共 N 条」，避免误以为无分页；
 * 单页时上一页 / 下一页按钮自动 disabled。fetching 时也 disabled 防抖动。
 */
export function TablePaginationFooter({ page, total, pageSize, fetching, onChange }: Props) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <div className="fx-pagination-footer">
      <button
        type="button"
        className="fx-btn-secondary fx-btn-xs fx-pagination-footer__btn"
        disabled={page <= 1 || fetching}
        onClick={() => onChange(Math.max(1, page - 1))}
      >
        上一页
      </button>
      <span className="fx-mono fx-pagination-footer__page">
        {page} / {totalPages}
      </span>
      <button
        type="button"
        className="fx-btn-secondary fx-btn-xs fx-pagination-footer__btn"
        disabled={page >= totalPages || fetching}
        onClick={() => onChange(Math.min(totalPages, page + 1))}
      >
        下一页
      </button>
      <span className="fx-mono fx-pagination-footer__total">
        共 {total} 条
      </span>
    </div>
  );
}
