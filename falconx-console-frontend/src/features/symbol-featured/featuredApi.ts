import { adminApi } from "../../lib/api/apiClient";
import type { FeaturedListResponse, FeaturedReplaceRequest } from "./types";

/** 跑马灯热门产品配置 API（透传 market owner，单一全局有序列表，全量替换）。 */
export const featuredApi = {
  list: () => adminApi.get<FeaturedListResponse>("/admin/symbols/featured"),

  replace: (body: FeaturedReplaceRequest) =>
    adminApi.put<FeaturedListResponse>("/admin/symbols/featured", body),
};
