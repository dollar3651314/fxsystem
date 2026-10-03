/** 跑马灯热门产品配置类型（管理端）。 */

export interface FeaturedItem {
  symbol: string;
  sortOrder: number;
  enabled: boolean;
}

export interface FeaturedListResponse {
  items: FeaturedItem[];
}

export interface FeaturedReplaceItem {
  symbol: string;
  enabled: boolean;
}

export interface FeaturedReplaceRequest {
  /** 顺序即展示序（sortOrder）。 */
  items: FeaturedReplaceItem[];
}
