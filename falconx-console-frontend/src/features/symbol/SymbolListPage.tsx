import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  TimePicker,
  Transfer,
  Typography,
  message,
} from "antd";
import type { TableProps } from "antd";
import dayjs, { Dayjs } from "dayjs";
import {
  EditOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SearchOutlined,
  ThunderboltOutlined,
  ClockCircleOutlined,
  CalendarOutlined,
  DeleteOutlined,
  LinkOutlined,
  EyeOutlined,
  PlusOutlined,
} from "@ant-design/icons";
import { RequiresPermission } from "../../components/RequiresPermission";
import { symbolApi } from "./symbolApi";
import {
  CATEGORY_NAMES,
  MARKET_CODE_OPTIONS,
  STATUS_NAMES,
  type MarketHolidayItem,
  type MarketHolidayQuery,
  type SymbolGroupVisibilityGroupedItem,
  type SymbolGroupVisibilityGroupedQuery,
  type SwapRateResponse,
  type SymbolListItem,
  type SymbolListQuery,
  type SymbolQuoteMappingItem,
  type SymbolQuoteMappingQuery,
  type SymbolSourceCreateRequest,
  type TradingExceptionUpsertRequest,
  type TradingHoursResponse,
  type TradingSessionItem,
  type TradingSessionUpsertRequest,
} from "./types";

const { Title, Text } = Typography;

const DAY_OF_WEEK_NAMES: Record<number, string> = {
  1: "周一",
  2: "周二",
  3: "周三",
  4: "周四",
  5: "周五",
  6: "周六",
  7: "周日",
};

const HOLIDAY_TYPE_NAMES: Record<number, string> = {
  1: "全天休市",
  2: "提前收盘",
  3: "晚开盘",
};

const EXCEPTION_TYPE_NAMES: Record<number, string> = {
  1: "全天休市",
  2: "特殊时段",
};

const DEFAULT_LP_CODE = "GODSA";

const parseTime = (value: string | null | undefined) => (value ? dayjs(`2000-01-01T${value}`) : undefined);

const formatTime = (value: Dayjs | undefined | null) => (value ? value.format("HH:mm:ss") : null);

/**
 * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：source 编辑字段裁剪后只剩元数据。
 */
interface UpdateFormValues {
  category: number;
  marketCode: string;
  pricePrecision: number;
  qtyPrecision: number;
  reason: string;
}

interface SourceCreateFormValues {
  lpCode: string;
  symbol: string;
  category: number;
  marketCode: string;
  baseCurrency: string;
  quoteCurrency: string;
  pricePrecision: number;
  qtyPrecision: number;
  reason: string;
}

interface SwapRateFormValues {
  longRate: number;
  shortRate: number;
  rolloverTime: string;
  effectiveFrom: Dayjs;
  reason: string;
}

interface TradingSessionFormValues {
  dayOfWeek: number;
  sessionNo: number;
  openTime: Dayjs;
  closeTime: Dayjs;
  timezone: string;
  enabled: boolean;
  effectiveFrom: Dayjs;
  effectiveTo?: Dayjs;
  reason: string;
}

interface TradingExceptionFormValues {
  tradeDate: Dayjs;
  exceptionType: number;
  sessionNo?: number;
  openTime?: Dayjs;
  closeTime?: Dayjs;
  timezone: string;
  ruleReason?: string;
  reason: string;
}

interface HolidayFormValues {
  marketCode: string;
  holidayDate: Dayjs;
  holidayType: number;
  openTime?: Dayjs;
  closeTime?: Dayjs;
  timezone: string;
  holidayName: string;
  countryCode?: string;
  reason: string;
}

interface HolidayDeleteFormValues {
  reason: string;
}

/**
 * STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10 + V15：mapping 字段扩展，系统级
 * category / marketCode / 6 交易参数 / precision 都在这里配置。
 */
interface MappingFormValues {
  platformSymbol: string;
  sourceProvider: string;
  sourceLpCode: string;
  sourceSymbol: string;
  category: number;
  marketCode: string;
  priceMultiplier: number;
  bidAdjustment: number;
  askAdjustment: number;
  enabled: boolean;
  lpSubscribeEnabled: boolean;
  maxLeverage: number;
  takerFeeRate: number;
  spread: number;
  minQty: number;
  maxQty: number;
  minNotional: number;
  pricePrecision: number;
  qtyPrecision: number;
  reason: string;
}

/**
 * 用户组可见性聚合视图编辑表单：groupCode（新建可编辑/编辑锁死）+ 操作原因，
 * 可见 symbol 集合由 Transfer 双列穿梭单独承载。
 */
interface VisibilityGroupFormValues {
  groupCode: string;
  reason: string;
}

/**
 * Transfer 左侧候选条目：来自 t_symbol_quote_mapping 平台 Symbol 池，
 * 标注启用/停用，便于运营识别"停用映射"风险。
 */
interface MappingTransferRecord {
  key: string;
  platformSymbol: string;
  sourceProvider: string;
  enabled: 0 | 1;
}

export function SymbolListPage() {
  const [items, setItems] = useState<SymbolListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<SymbolListQuery>({ page: 0, size: 20 });

  // 编辑 Drawer
  const [editing, setEditing] = useState<SymbolListItem | null>(null);
  const [editSubmitting, setEditSubmitting] = useState(false);
  const [editForm] = Form.useForm<UpdateFormValues>();

  // LP 源 Symbol 新建 Drawer
  const [sourceCreateOpen, setSourceCreateOpen] = useState(false);
  const [sourceCreateSubmitting, setSourceCreateSubmitting] = useState(false);
  const [sourceCreateForm] = Form.useForm<SourceCreateFormValues>();

  // 暂停 / 恢复高风险确认
  const [pauseTarget, setPauseTarget] = useState<SymbolListItem | null>(null);
  const [pauseReason, setPauseReason] = useState("");
  const [pauseSubmitting, setPauseSubmitting] = useState(false);

  // Swap Rate Drawer
  const [swapRateTarget, setSwapRateTarget] = useState<string | null>(null);
  const [swapRateData, setSwapRateData] = useState<SwapRateResponse | null>(null);
  const [swapRateSubmitting, setSwapRateSubmitting] = useState(false);
  const [swapRateForm] = Form.useForm<SwapRateFormValues>();

  // Trading Hours Modal
  const [hoursTarget, setHoursTarget] = useState<string | null>(null);
  const [hoursData, setHoursData] = useState<TradingHoursResponse | null>(null);
  const [sessionTarget, setSessionTarget] = useState<TradingSessionItem | null>(null);
  const [sessionDrawerOpen, setSessionDrawerOpen] = useState(false);
  const [sessionSubmitting, setSessionSubmitting] = useState(false);
  const [sessionForm] = Form.useForm<TradingSessionFormValues>();
  const [exceptionTarget, setExceptionTarget] = useState<TradingHoursResponse["exceptions"][number] | null>(null);
  const [exceptionDrawerOpen, setExceptionDrawerOpen] = useState(false);
  const [exceptionSubmitting, setExceptionSubmitting] = useState(false);
  const [exceptionForm] = Form.useForm<TradingExceptionFormValues>();

  // Quote Mapping
  const [mappings, setMappings] = useState<SymbolQuoteMappingItem[]>([]);
  const [mappingTotal, setMappingTotal] = useState(0);
  const [mappingPage, setMappingPage] = useState(0);
  const [mappingSize, setMappingSize] = useState(20);
  const [mappingLoading, setMappingLoading] = useState(false);
  const [mappingQuery, setMappingQuery] = useState<SymbolQuoteMappingQuery>({ page: 0, size: 20 });
  const [mappingTarget, setMappingTarget] = useState<SymbolQuoteMappingItem | null>(null);
  const [mappingDrawerOpen, setMappingDrawerOpen] = useState(false);
  const [mappingSubmitting, setMappingSubmitting] = useState(false);
  const [mappingForm] = Form.useForm<MappingFormValues>();
  const [sourceOptions, setSourceOptions] = useState<SymbolListItem[]>([]);
  const [sourceOptionsLoading, setSourceOptionsLoading] = useState(false);
  const [sourceSelectValue, setSourceSelectValue] = useState<string | undefined>();

  // Group Visibility（聚合视图：每组一行 + Transfer 双列穿梭编辑）
  const [visibilityItems, setVisibilityItems] = useState<SymbolGroupVisibilityGroupedItem[]>([]);
  const [visibilityTotal, setVisibilityTotal] = useState(0);
  const [visibilityPage, setVisibilityPage] = useState(0);
  const [visibilitySize, setVisibilitySize] = useState(20);
  const [visibilityLoading, setVisibilityLoading] = useState(false);
  const [visibilityQuery, setVisibilityQuery] = useState<SymbolGroupVisibilityGroupedQuery>({ page: 0, size: 20 });
  const [visibilityTarget, setVisibilityTarget] = useState<SymbolGroupVisibilityGroupedItem | null>(null);
  const [visibilityDrawerOpen, setVisibilityDrawerOpen] = useState(false);
  const [visibilitySubmitting, setVisibilitySubmitting] = useState(false);
  const [visibilityForm] = Form.useForm<VisibilityGroupFormValues>();
  // Transfer 右侧已选 + 打开 Drawer 时记录原始已选用于 diff
  const [visibilityTargetKeys, setVisibilityTargetKeys] = useState<string[]>([]);
  const [visibilityOriginalKeys, setVisibilityOriginalKeys] = useState<string[]>([]);
  // 平台 Symbol 映射池：所有 mapping 行（含 enabled 标注），用作 Transfer 左侧候选
  const [mappingPool, setMappingPool] = useState<MappingTransferRecord[]>([]);
  const [mappingPoolLoading, setMappingPoolLoading] = useState(false);

  // Market Holiday
  const [holidayItems, setHolidayItems] = useState<MarketHolidayItem[]>([]);
  const [holidayTotal, setHolidayTotal] = useState(0);
  const [holidayPage, setHolidayPage] = useState(0);
  const [holidaySize, setHolidaySize] = useState(20);
  const [holidayLoading, setHolidayLoading] = useState(false);
  const [holidayQuery, setHolidayQuery] = useState<MarketHolidayQuery>({ page: 0, size: 20 });
  const [holidayTarget, setHolidayTarget] = useState<MarketHolidayItem | null>(null);
  const [holidayDrawerOpen, setHolidayDrawerOpen] = useState(false);
  const [holidaySubmitting, setHolidaySubmitting] = useState(false);
  const [holidayForm] = Form.useForm<HolidayFormValues>();
  const [holidayDeleteTarget, setHolidayDeleteTarget] = useState<MarketHolidayItem | null>(null);
  const [holidayDeleteSubmitting, setHolidayDeleteSubmitting] = useState(false);
  const [holidayDeleteForm] = Form.useForm<HolidayDeleteFormValues>();

  const load = (q: SymbolListQuery) => {
    setLoading(true);
    symbolApi
      .list(q)
      .then((data) => {
        setItems(data.items);
        setTotal(data.total);
        setPage(data.page);
        setSize(data.size);
      })
      .catch((err) => message.error(`加载失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load(query);
  }, [query]);

  const loadMappings = (q: SymbolQuoteMappingQuery) => {
    setMappingLoading(true);
    symbolApi
      .listQuoteMappings(q)
      .then((data) => {
        setMappings(data.items);
        setMappingTotal(data.total);
        setMappingPage(data.page);
        setMappingSize(data.size);
      })
      .catch((err) => message.error(`加载映射失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setMappingLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadMappings(mappingQuery);
  }, [mappingQuery]);

  const sourceOptionValue = (item: SymbolListItem) =>
    JSON.stringify([item.lpCode, item.symbol]);

  const parseSourceOptionValue = (value: string) => {
    const [lpCode, symbol] = JSON.parse(value) as [string, string];
    return {
      lpCode: lpCode || DEFAULT_LP_CODE,
      symbol: symbol || "",
    };
  };

  const loadSourceOptions = async (symbolLike?: string, lpCode?: string) => {
    setSourceOptionsLoading(true);
    try {
      const resp = await symbolApi.list({
        lpCode: lpCode?.trim() || undefined,
        symbolLike: symbolLike?.trim() || undefined,
        page: 0,
        size: 100,
      });
      setSourceOptions(resp.items);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`加载源 Symbol 失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSourceOptionsLoading(false);
    }
  };

  const applySourceDefaults = (symbol: string, lpCode: string) => {
    const source = sourceOptions.find((item) => item.lpCode === lpCode && item.symbol === symbol);
    if (!source) return;
    mappingForm.setFieldsValue({
      category: source.category,
      marketCode: source.marketCode,
      pricePrecision: source.pricePrecision,
      qtyPrecision: source.qtyPrecision,
    });
  };

  const loadVisibility = (q: SymbolGroupVisibilityGroupedQuery) => {
    setVisibilityLoading(true);
    symbolApi
      .listGroupVisibilityGrouped(q)
      .then((data) => {
        setVisibilityItems(data.items);
        setVisibilityTotal(data.total);
        setVisibilityPage(data.page);
        setVisibilitySize(data.size);
      })
      .catch((err) => message.error(`加载可见性失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setVisibilityLoading(false));
  };

  /**
   * 加载 Transfer 左侧映射池：首页取 total，再并发拉完剩余 page，size 上限 100。
   * 当前线上 ≈ 1571 symbol → 16 个并发请求，可接受。
   */
  const loadMappingPool = async () => {
    setMappingPoolLoading(true);
    try {
      const first = await symbolApi.listQuoteMappings({ page: 0, size: 100 });
      const all: SymbolQuoteMappingItem[] = [...first.items];
      const totalPages = Math.ceil(first.total / 100);
      if (totalPages > 1) {
        const rest = await Promise.all(
          Array.from({ length: totalPages - 1 }, (_, i) =>
            symbolApi.listQuoteMappings({ page: i + 1, size: 100 }),
          ),
        );
        rest.forEach((r) => all.push(...r.items));
      }
      const pool: MappingTransferRecord[] = all.map((m) => ({
        key: m.platformSymbol,
        platformSymbol: m.platformSymbol,
        sourceProvider: m.sourceProvider,
        enabled: m.enabled,
      }));
      setMappingPool(pool);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`加载映射池失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setMappingPoolLoading(false);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadVisibility(visibilityQuery);
  }, [visibilityQuery]);

  const loadHolidays = (q: MarketHolidayQuery) => {
    setHolidayLoading(true);
    symbolApi
      .listMarketHolidays(q)
      .then((data) => {
        setHolidayItems(data.items);
        setHolidayTotal(data.total);
        setHolidayPage(data.page);
        setHolidaySize(data.size);
      })
      .catch((err) => message.error(`加载市场假期失败 ${err.code ?? ""}：${err.message ?? ""}`))
      .finally(() => setHolidayLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadHolidays(holidayQuery);
  }, [holidayQuery]);

  const handleSearch = (values: SymbolListQuery) => {
    setQuery({ ...query, ...values, page: 0 });
  };

  const handleMappingSearch = (values: SymbolQuoteMappingQuery) => {
    setMappingQuery({ ...mappingQuery, ...values, page: 0 });
  };

  const handleVisibilitySearch = (values: SymbolGroupVisibilityGroupedQuery) => {
    setVisibilityQuery({ ...visibilityQuery, ...values, page: 0 });
  };

  const handleHolidaySearch = (values: MarketHolidayQuery) => {
    setHolidayQuery({ ...holidayQuery, ...values, page: 0 });
  };

  const openEdit = (record: SymbolListItem) => {
    setEditing(record);
    // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10：source 编辑字段裁剪（仅 category/marketCode/precision），
    // 交易参数（leverage/fee/spread/qty）移到 mapping CRUD。
    editForm.setFieldsValue({
      category: record.category,
      marketCode: record.marketCode,
      pricePrecision: record.pricePrecision,
      qtyPrecision: record.qtyPrecision,
      reason: "",
    });
  };

  const submitEdit = async () => {
    if (!editing) return;
    const values = await editForm.validateFields();
    setEditSubmitting(true);
    try {
      await symbolApi.update(editing.id, {
        category: values.category,
        marketCode: values.marketCode,
        pricePrecision: values.pricePrecision,
        qtyPrecision: values.qtyPrecision,
        reason: values.reason,
      });
      message.success(`${editing.symbol} 配置已更新`);
      setEditing(null);
      load(query);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setEditSubmitting(false);
    }
  };

  const openCreateSource = () => {
    sourceCreateForm.setFieldsValue({
      lpCode: DEFAULT_LP_CODE,
      symbol: "",
      category: undefined,
      marketCode: undefined,
      baseCurrency: "",
      quoteCurrency: "USD",
      pricePrecision: 2,
      qtyPrecision: 2,
      reason: "",
    });
    setSourceCreateOpen(true);
  };

  const submitCreateSource = async () => {
    const values = await sourceCreateForm.validateFields();
    setSourceCreateSubmitting(true);
    try {
      const body: SymbolSourceCreateRequest = {
        lpCode: values.lpCode.trim(),
        symbol: values.symbol.trim(),
        category: values.category,
        marketCode: values.marketCode,
        baseCurrency: values.baseCurrency.trim(),
        quoteCurrency: values.quoteCurrency.trim(),
        pricePrecision: values.pricePrecision,
        qtyPrecision: values.qtyPrecision,
        reason: values.reason,
      };
      await symbolApi.createSource(body);
      message.success(`${body.symbol} 已创建`);
      setSourceCreateOpen(false);
      load(query);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`创建失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSourceCreateSubmitting(false);
    }
  };

  const openPauseModal = (record: SymbolListItem) => {
    setPauseTarget(record);
    setPauseReason("");
  };

  const submitPause = async () => {
    if (!pauseTarget) return;
    setPauseSubmitting(true);
    try {
      const isSuspend = pauseTarget.status === 1;
      if (isSuspend) {
        await symbolApi.suspend(pauseTarget.id, { reason: pauseReason });
      } else {
        await symbolApi.resume(pauseTarget.id, { reason: pauseReason });
      }
      message.success(`${pauseTarget.symbol} 已${isSuspend ? "暂停" : "恢复"}`);
      setPauseTarget(null);
      setPauseReason("");
      load(query);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`操作失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setPauseSubmitting(false);
    }
  };

  const openSwapRate = async (platformSymbol: string) => {
    setSwapRateTarget(platformSymbol);
    setSwapRateData(null);
    swapRateForm.resetFields();
    try {
      const data = await symbolApi.getSwapRate(platformSymbol);
      setSwapRateData(data);
      swapRateForm.setFieldsValue({
        longRate: data.current ? Number(data.current.longRate) : 0,
        shortRate: data.current ? Number(data.current.shortRate) : 0,
        rolloverTime: data.current?.rolloverTime ?? "22:00:00",
        effectiveFrom: dayjs().add(1, "day"),
        reason: "",
      });
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`加载费率失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const submitSwapRate = async () => {
    if (!swapRateTarget) return;
    const values = await swapRateForm.validateFields();
    setSwapRateSubmitting(true);
    try {
      await symbolApi.putSwapRate(swapRateTarget, {
        longRate: String(values.longRate),
        shortRate: String(values.shortRate),
        rolloverTime: values.rolloverTime || undefined,
        effectiveFrom: values.effectiveFrom.format("YYYY-MM-DD"),
        reason: values.reason,
      });
      message.success(`${swapRateTarget} 隔夜费率已更新`);
      const refreshed = await symbolApi.getSwapRate(swapRateTarget);
      setSwapRateData(refreshed);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSwapRateSubmitting(false);
    }
  };

  const openTradingHours = async (platformSymbol: string) => {
    setHoursTarget(platformSymbol);
    setHoursData(null);
    try {
      const data = await symbolApi.getTradingHours(platformSymbol);
      setHoursData(data);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`加载交易时段失败 ${e.code ?? ""}：${e.message ?? ""}`);
    }
  };

  const refreshTradingHours = async () => {
    if (!hoursTarget) return;
    const data = await symbolApi.getTradingHours(hoursTarget);
    setHoursData(data);
  };

  const openCreateSession = () => {
    setSessionTarget(null);
    sessionForm.setFieldsValue({
      dayOfWeek: 1,
      sessionNo: 1,
      openTime: parseTime("00:00:00"),
      closeTime: parseTime("23:59:59"),
      timezone: "UTC",
      enabled: true,
      effectiveFrom: dayjs(),
      effectiveTo: undefined,
      reason: "",
    });
    setSessionDrawerOpen(true);
  };

  const openEditSession = (record: TradingSessionItem) => {
    setSessionTarget(record);
    sessionForm.setFieldsValue({
      dayOfWeek: record.dayOfWeek,
      sessionNo: record.sessionNo,
      openTime: parseTime(record.openTime),
      closeTime: parseTime(record.closeTime),
      timezone: record.timezone,
      enabled: record.enabled,
      effectiveFrom: dayjs(record.effectiveFrom),
      effectiveTo: record.effectiveTo ? dayjs(record.effectiveTo) : undefined,
      reason: "",
    });
    setSessionDrawerOpen(true);
  };

  const submitSession = async () => {
    if (!hoursTarget) return;
    const values = await sessionForm.validateFields();
    const body: TradingSessionUpsertRequest = {
      dayOfWeek: values.dayOfWeek,
      sessionNo: values.sessionNo,
      openTime: formatTime(values.openTime) ?? "00:00:00",
      closeTime: formatTime(values.closeTime) ?? "00:00:00",
      timezone: values.timezone,
      enabled: values.enabled ? 1 : 0,
      effectiveFrom: values.effectiveFrom.format("YYYY-MM-DD"),
      effectiveTo: values.effectiveTo ? values.effectiveTo.format("YYYY-MM-DD") : null,
      reason: values.reason,
    };
    setSessionSubmitting(true);
    try {
      if (sessionTarget) {
        await symbolApi.updateTradingSession(hoursTarget, sessionTarget.id, body);
        message.success("交易时段已更新");
      } else {
        await symbolApi.createTradingSession(hoursTarget, body);
        message.success("交易时段已新增");
      }
      setSessionDrawerOpen(false);
      setSessionTarget(null);
      await refreshTradingHours();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存交易时段失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setSessionSubmitting(false);
    }
  };

  const confirmDeleteSession = (record: TradingSessionItem) => {
    if (!hoursTarget) return;
    let reason = "";
    Modal.confirm({
      title: `删除 ${DAY_OF_WEEK_NAMES[record.dayOfWeek] ?? record.dayOfWeek} 第 ${record.sessionNo} 段交易时段`,
      content: (
        <Input.TextArea
          rows={3}
          placeholder="操作原因（必填，≥ 10 字符）"
          onChange={(event) => {
            reason = event.target.value;
          }}
        />
      ),
      okText: "确认删除",
      okType: "danger",
      onOk: async () => {
        if (reason.trim().length < 10) {
          message.error("操作原因至少 10 字符");
          throw new Error("reason too short");
        }
        await symbolApi.deleteTradingSession(hoursTarget, record.id, { reason });
        message.success("交易时段已删除");
        await refreshTradingHours();
      },
    });
  };

  const openCreateException = () => {
    setExceptionTarget(null);
    exceptionForm.setFieldsValue({
      tradeDate: dayjs(),
      exceptionType: 1,
      sessionNo: undefined,
      openTime: undefined,
      closeTime: undefined,
      timezone: "UTC",
      ruleReason: "",
      reason: "",
    });
    setExceptionDrawerOpen(true);
  };

  const openEditException = (record: TradingHoursResponse["exceptions"][number]) => {
    setExceptionTarget(record);
    exceptionForm.setFieldsValue({
      tradeDate: dayjs(record.tradeDate),
      exceptionType: record.exceptionType,
      sessionNo: record.sessionNo ?? undefined,
      openTime: parseTime(record.openTime),
      closeTime: parseTime(record.closeTime),
      timezone: record.timezone,
      ruleReason: record.reason ?? "",
      reason: "",
    });
    setExceptionDrawerOpen(true);
  };

  const submitException = async () => {
    if (!hoursTarget) return;
    const values = await exceptionForm.validateFields();
    const body: TradingExceptionUpsertRequest = {
      tradeDate: values.tradeDate.format("YYYY-MM-DD"),
      exceptionType: values.exceptionType,
      sessionNo: values.sessionNo ?? null,
      openTime: formatTime(values.openTime),
      closeTime: formatTime(values.closeTime),
      timezone: values.timezone,
      ruleReason: values.ruleReason?.trim() || null,
      reason: values.reason,
    };
    setExceptionSubmitting(true);
    try {
      if (exceptionTarget) {
        await symbolApi.updateTradingException(hoursTarget, exceptionTarget.id, body);
        message.success("特殊交易日已更新");
      } else {
        await symbolApi.createTradingException(hoursTarget, body);
        message.success("特殊交易日已新增");
      }
      setExceptionDrawerOpen(false);
      setExceptionTarget(null);
      await refreshTradingHours();
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存特殊交易日失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setExceptionSubmitting(false);
    }
  };

  const confirmDeleteException = (record: TradingHoursResponse["exceptions"][number]) => {
    if (!hoursTarget) return;
    let reason = "";
    Modal.confirm({
      title: `删除 ${record.tradeDate} 特殊交易日`,
      content: (
        <Input.TextArea
          rows={3}
          placeholder="操作原因（必填，≥ 10 字符）"
          onChange={(event) => {
            reason = event.target.value;
          }}
        />
      ),
      okText: "确认删除",
      okType: "danger",
      onOk: async () => {
        if (reason.trim().length < 10) {
          message.error("操作原因至少 10 字符");
          throw new Error("reason too short");
        }
        await symbolApi.deleteTradingException(hoursTarget, record.id, { reason });
        message.success("特殊交易日已删除");
        await refreshTradingHours();
      },
    });
  };

  const openCreateMapping = () => {
    setMappingTarget(null);
    setSourceSelectValue(undefined);
    mappingForm.setFieldsValue({
      platformSymbol: "",
      sourceProvider: "LP",
      sourceLpCode: DEFAULT_LP_CODE,
      sourceSymbol: "",
      category: undefined,
      marketCode: undefined,
      priceMultiplier: 1,
      bidAdjustment: 0,
      askAdjustment: 0,
      enabled: true,
      lpSubscribeEnabled: true,
      // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 下沉的 6 交易字段创建时给合理默认；
      // category / marketCode / precision 从源 Symbol 选择后自动带入，运营可在 mapping 中调整。
      maxLeverage: 100,
      takerFeeRate: 0,
      spread: 0,
      minQty: 0.01,
      maxQty: 1000000,
      minNotional: 0,
      pricePrecision: undefined,
      qtyPrecision: undefined,
      reason: "",
    });
    setMappingDrawerOpen(true);
    void loadSourceOptions(undefined, DEFAULT_LP_CODE);
  };

  const openEditMapping = (record: SymbolQuoteMappingItem) => {
    setMappingTarget(record);
    setSourceSelectValue(JSON.stringify([record.sourceLpCode, record.sourceSymbol]));
    mappingForm.setFieldsValue({
      platformSymbol: record.platformSymbol,
      sourceProvider: record.sourceProvider,
      sourceLpCode: record.sourceLpCode,
      sourceSymbol: record.sourceSymbol,
      category: record.category,
      marketCode: record.marketCode,
      priceMultiplier: Number(record.priceMultiplier),
      bidAdjustment: Number(record.bidAdjustment),
      askAdjustment: Number(record.askAdjustment),
      enabled: record.enabled === 1,
      lpSubscribeEnabled: record.lpSubscribeEnabled === 1,
      // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 8 字段回填：之前漏传导致 Drawer 显示空白
      // 且必填校验失败无法保存（min_notional 等无法运营动态调整）
      maxLeverage: Number(record.maxLeverage),
      takerFeeRate: Number(record.takerFeeRate),
      spread: Number(record.spread),
      minQty: Number(record.minQty),
      maxQty: Number(record.maxQty),
      minNotional: Number(record.minNotional),
      pricePrecision: record.pricePrecision,
      qtyPrecision: record.qtyPrecision,
      reason: "",
    });
    setMappingDrawerOpen(true);
    void loadSourceOptions(record.sourceSymbol, record.sourceLpCode);
  };

  const submitMapping = async () => {
    if (!mappingForm.getFieldValue("sourceLpCode") || !mappingForm.getFieldValue("sourceSymbol")) {
      message.error("请选择已存在的源 Symbol");
      return;
    }
    const values = await mappingForm.validateFields();
    setMappingSubmitting(true);
    try {
      if (mappingTarget) {
        // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R10 + V15：mapping 字段集扩展。
        await symbolApi.updateQuoteMapping(mappingTarget.platformSymbol, {
          sourceProvider: values.sourceProvider,
          sourceLpCode: values.sourceLpCode,
          sourceSymbol: values.sourceSymbol,
          category: values.category,
          marketCode: values.marketCode,
          priceMultiplier: String(values.priceMultiplier),
          bidAdjustment: String(values.bidAdjustment),
          askAdjustment: String(values.askAdjustment),
          enabled: values.enabled ? 1 : 0,
          lpSubscribeEnabled: values.lpSubscribeEnabled ? 1 : 0,
          maxLeverage: values.maxLeverage,
          takerFeeRate: String(values.takerFeeRate),
          spread: String(values.spread),
          minQty: String(values.minQty),
          maxQty: String(values.maxQty),
          minNotional: String(values.minNotional),
          pricePrecision: values.pricePrecision,
          qtyPrecision: values.qtyPrecision,
          reason: values.reason,
        });
        message.success(`${mappingTarget.platformSymbol} 映射已更新`);
      } else {
        await symbolApi.createQuoteMapping({
          platformSymbol: values.platformSymbol,
          sourceProvider: values.sourceProvider,
          sourceLpCode: values.sourceLpCode,
          sourceSymbol: values.sourceSymbol,
          category: values.category,
          marketCode: values.marketCode,
          priceMultiplier: String(values.priceMultiplier),
          bidAdjustment: String(values.bidAdjustment),
          askAdjustment: String(values.askAdjustment),
          enabled: values.enabled ? 1 : 0,
          lpSubscribeEnabled: values.lpSubscribeEnabled ? 1 : 0,
          maxLeverage: values.maxLeverage,
          takerFeeRate: String(values.takerFeeRate),
          spread: String(values.spread),
          minQty: String(values.minQty),
          maxQty: String(values.maxQty),
          minNotional: String(values.minNotional),
          pricePrecision: values.pricePrecision,
          qtyPrecision: values.qtyPrecision,
          reason: values.reason,
        });
        message.success(`${values.platformSymbol} 映射已创建`);
      }
      setMappingDrawerOpen(false);
      setMappingTarget(null);
      loadMappings(mappingQuery);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存映射失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setMappingSubmitting(false);
    }
  };

  const openCreateVisibility = () => {
    setVisibilityTarget(null);
    visibilityForm.setFieldsValue({ groupCode: "", reason: "" });
    setVisibilityTargetKeys([]);
    setVisibilityOriginalKeys([]);
    setVisibilityDrawerOpen(true);
    if (mappingPool.length === 0) void loadMappingPool();
  };

  const openEditVisibility = (record: SymbolGroupVisibilityGroupedItem) => {
    setVisibilityTarget(record);
    visibilityForm.setFieldsValue({ groupCode: record.groupCode, reason: "" });
    setVisibilityTargetKeys([...record.visibleSymbols]);
    setVisibilityOriginalKeys([...record.visibleSymbols]);
    setVisibilityDrawerOpen(true);
    if (mappingPool.length === 0) void loadMappingPool();
  };

  /**
   * 保存逻辑：对比 original ∩ target 计算 added / removed，
   * 对 added 调 bulk visible=1，对 removed 调 bulk visible=0，
   * 两次调用任一失败都中止并提示。
   */
  const submitVisibility = async () => {
    const values = await visibilityForm.validateFields();
    const original = new Set(visibilityOriginalKeys);
    const target = new Set(visibilityTargetKeys);
    const added = [...target].filter((k) => !original.has(k));
    const removed = [...original].filter((k) => !target.has(k));
    if (added.length === 0 && removed.length === 0) {
      message.warning("Symbol 集合未变化，无需保存");
      return;
    }
    setVisibilitySubmitting(true);
    try {
      if (added.length > 0) {
        await symbolApi.bulkUpsertGroupVisibility(values.groupCode, {
          symbols: added,
          visible: 1,
          reason: values.reason,
        });
      }
      if (removed.length > 0) {
        await symbolApi.bulkUpsertGroupVisibility(values.groupCode, {
          symbols: removed,
          visible: 0,
          reason: values.reason,
        });
      }
      message.success(
        `${values.groupCode} 已保存：新增 ${added.length} 个 / 移除 ${removed.length} 个`,
      );
      setVisibilityDrawerOpen(false);
      setVisibilityTarget(null);
      loadVisibility(visibilityQuery);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setVisibilitySubmitting(false);
    }
  };

  const openCreateHoliday = () => {
    setHolidayTarget(null);
    holidayForm.setFieldsValue({
      marketCode: "US_STOCK",
      holidayDate: dayjs(),
      holidayType: 1,
      openTime: undefined,
      closeTime: undefined,
      timezone: "America/New_York",
      holidayName: "",
      countryCode: "US",
      reason: "",
    });
    setHolidayDrawerOpen(true);
  };

  const openEditHoliday = (record: MarketHolidayItem) => {
    setHolidayTarget(record);
    holidayForm.setFieldsValue({
      marketCode: record.marketCode,
      holidayDate: dayjs(record.holidayDate),
      holidayType: record.holidayType,
      openTime: parseTime(record.openTime),
      closeTime: parseTime(record.closeTime),
      timezone: record.timezone,
      holidayName: record.holidayName,
      countryCode: record.countryCode ?? "",
      reason: "",
    });
    setHolidayDrawerOpen(true);
  };

  const submitHoliday = async () => {
    const values = await holidayForm.validateFields();
    const body = {
      marketCode: values.marketCode,
      holidayDate: values.holidayDate.format("YYYY-MM-DD"),
      holidayType: values.holidayType,
      openTime: formatTime(values.openTime),
      closeTime: formatTime(values.closeTime),
      timezone: values.timezone,
      holidayName: values.holidayName,
      countryCode: values.countryCode?.trim() || null,
      reason: values.reason,
    };
    setHolidaySubmitting(true);
    try {
      if (holidayTarget) {
        await symbolApi.updateMarketHoliday(holidayTarget.id, body);
        message.success("市场假期已更新");
      } else {
        await symbolApi.createMarketHoliday(body);
        message.success("市场假期已新增");
      }
      setHolidayDrawerOpen(false);
      setHolidayTarget(null);
      loadHolidays(holidayQuery);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`保存市场假期失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setHolidaySubmitting(false);
    }
  };

  const openDeleteHoliday = (record: MarketHolidayItem) => {
    setHolidayDeleteTarget(record);
    holidayDeleteForm.resetFields();
  };

  const submitDeleteHoliday = async () => {
    if (!holidayDeleteTarget) return;
    const values = await holidayDeleteForm.validateFields();
    setHolidayDeleteSubmitting(true);
    try {
      await symbolApi.deleteMarketHoliday(holidayDeleteTarget.id, { reason: values.reason.trim() });
      message.success("市场假期已删除");
      setHolidayDeleteTarget(null);
      loadHolidays(holidayQuery);
    } catch (err) {
      const e = err as { code?: string; message?: string };
      message.error(`删除市场假期失败 ${e.code ?? ""}：${e.message ?? ""}`);
    } finally {
      setHolidayDeleteSubmitting(false);
    }
  };

  const columns: TableProps<SymbolListItem>["columns"] = [
    {
      title: "Symbol",
      dataIndex: "symbol",
      key: "symbol",
      width: 160,
      render: (s: string) => <code>{s}</code>,
    },
    {
      title: "LP",
      dataIndex: "lpCode",
      key: "lpCode",
      width: 110,
      render: (v: string) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: "类别",
      dataIndex: "category",
      key: "category",
      width: 90,
      render: (c: number) => <Tag>{CATEGORY_NAMES[c] ?? c}</Tag>,
    },
    { title: "市场", dataIndex: "marketCode", key: "marketCode", width: 100 },
    {
      title: "对",
      key: "pair",
      width: 130,
      render: (_, record) => (
        <span style={{ fontFamily: "monospace" }}>
          {record.baseCurrency}/{record.quoteCurrency}
        </span>
      ),
    },
    // V11 STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后 maxLeverage / takerFeeRate / spread / minQty
    // / maxQty / minNotional 已从 t_symbol 下沉到 t_symbol_quote_mapping，主表 record
    // 不再持有这些字段。请到"报价映射" tab 编辑对应 mapping 配置交易参数。
    {
      title: "状态",
      dataIndex: "status",
      key: "status",
      width: 110,
      render: (s: 1 | 2) =>
        s === 1 ? <Tag color="green">{STATUS_NAMES[s]}</Tag> : <Tag color="red">{STATUS_NAMES[s]}</Tag>,
    },
    {
      title: "操作",
      key: "actions",
      width: 220,
      render: (_, record) => (
        <Space size={4}>
          <RequiresPermission code="symbol:source:update">
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
              编辑
            </Button>
          </RequiresPermission>
          <RequiresPermission code="symbol:suspend">
            <Button
              size="small"
              icon={record.status === 1 ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
              danger={record.status === 1}
              loading={pauseSubmitting && pauseTarget?.id === record.id}
              onClick={() => openPauseModal(record)}
            >
              {record.status === 1 ? "暂停" : "恢复"}
            </Button>
          </RequiresPermission>
        </Space>
      ),
    },
  ];

  const mappingColumns: TableProps<SymbolQuoteMappingItem>["columns"] = [
    {
      title: "平台 Symbol",
      dataIndex: "platformSymbol",
      key: "platformSymbol",
      width: 160,
      render: (v: string) => <code>{v}</code>,
    },
    {
      title: "源 Symbol",
      dataIndex: "sourceSymbol",
      key: "sourceSymbol",
      width: 160,
      render: (v: string) => <code>{v}</code>,
    },
    {
      title: "源 LP",
      dataIndex: "sourceLpCode",
      key: "sourceLpCode",
      width: 110,
      render: (v: string) => <Tag color="blue">{v}</Tag>,
    },
    { title: "Provider", dataIndex: "sourceProvider", key: "sourceProvider", width: 110 },
    {
      title: "类别",
      dataIndex: "category",
      key: "category",
      width: 90,
      render: (v: number) => <Tag>{CATEGORY_NAMES[v] ?? v}</Tag>,
    },
    { title: "市场", dataIndex: "marketCode", key: "marketCode", width: 110 },
    {
      title: "精度",
      key: "precision",
      width: 90,
      render: (_, record) => `${record.pricePrecision}/${record.qtyPrecision}`,
    },
    { title: "乘数", dataIndex: "priceMultiplier", key: "priceMultiplier", width: 110, align: "right" },
    { title: "Bid 加点", dataIndex: "bidAdjustment", key: "bidAdjustment", width: 110, align: "right" },
    { title: "Ask 加点", dataIndex: "askAdjustment", key: "askAdjustment", width: 110, align: "right" },
    {
      title: "映射",
      dataIndex: "enabled",
      key: "enabled",
      width: 90,
      render: (v: 0 | 1) => (v === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>),
    },
    {
      title: "LP订阅",
      dataIndex: "lpSubscribeEnabled",
      key: "lpSubscribeEnabled",
      width: 100,
      render: (v: 0 | 1) => (v === 1 ? <Tag color="green">订阅</Tag> : <Tag color="default">不订阅</Tag>),
    },
    {
      title: "源状态",
      dataIndex: "sourceStatus",
      key: "sourceStatus",
      width: 110,
      render: (v: 1 | 2 | null) =>
        v === 1 ? <Tag color="green">TRADING</Tag> : v === 2 ? <Tag color="red">SUSPENDED</Tag> : <Tag>缺失</Tag>,
    },
    {
      title: "操作",
      key: "actions",
      width: 260,
      render: (_, record) => (
        <Space size={4}>
          <RequiresPermission code="symbol:quote-mapping:update">
            <Button size="small" icon={<EditOutlined />} onClick={() => openEditMapping(record)}>
              编辑
            </Button>
          </RequiresPermission>
          <RequiresPermission code="symbol:swap-rate:update">
            <Button size="small" icon={<ThunderboltOutlined />} onClick={() => openSwapRate(record.platformSymbol)}>
              Swap
            </Button>
          </RequiresPermission>
          <Button size="small" icon={<ClockCircleOutlined />} onClick={() => openTradingHours(record.platformSymbol)}>
            时段
          </Button>
        </Space>
      ),
    },
  ];

  const visibilityColumns: TableProps<SymbolGroupVisibilityGroupedItem>["columns"] = [
    { title: "用户组", dataIndex: "groupCode", key: "groupCode", width: 160 },
    {
      title: "可见数",
      dataIndex: "visibleCount",
      key: "visibleCount",
      width: 100,
      render: (v: number) => <Tag color="blue">{v}</Tag>,
    },
    {
      title: "Symbol 预览（前 5 个）",
      dataIndex: "visibleSymbols",
      key: "visibleSymbols",
      render: (symbols: string[]) => {
        if (!symbols || symbols.length === 0) return <Text type="secondary">无</Text>;
        const preview = symbols.slice(0, 5);
        const more = symbols.length - preview.length;
        return (
          <Space wrap size={[4, 4]}>
            {preview.map((s) => (
              <Tag key={s}>{s}</Tag>
            ))}
            {more > 0 && <Text type="secondary">+{more}</Text>}
          </Space>
        );
      },
    },
    {
      title: "最近更新",
      dataIndex: "lastModifiedAt",
      key: "lastModifiedAt",
      width: 200,
      render: (v: string | null) => (v ? v.replace("T", " ").slice(0, 19) : <Text type="secondary">—</Text>),
    },
    {
      title: "操作",
      key: "actions",
      width: 110,
      render: (_, record) => (
        <RequiresPermission code="symbol:group-visibility:update">
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditVisibility(record)}>
            编辑
          </Button>
        </RequiresPermission>
      ),
    },
  ];

  const holidayColumns: TableProps<MarketHolidayItem>["columns"] = [
    { title: "市场", dataIndex: "marketCode", key: "marketCode", width: 120 },
    { title: "日期", dataIndex: "holidayDate", key: "holidayDate", width: 130 },
    {
      title: "类型",
      dataIndex: "holidayType",
      key: "holidayType",
      width: 120,
      render: (v: number) => HOLIDAY_TYPE_NAMES[v] ?? v,
    },
    { title: "名称", dataIndex: "holidayName", key: "holidayName", width: 180 },
    {
      title: "时间",
      key: "window",
      width: 160,
      render: (_, record) => `${record.openTime ?? "—"} / ${record.closeTime ?? "—"}`,
    },
    { title: "时区", dataIndex: "timezone", key: "timezone", width: 160 },
    { title: "地区", dataIndex: "countryCode", key: "countryCode", width: 90 },
    {
      title: "操作",
      key: "actions",
      width: 150,
      render: (_, record) => (
        <RequiresPermission code="symbol:holiday:update">
          <Space size={4}>
            <Button size="small" icon={<EditOutlined />} onClick={() => openEditHoliday(record)}>
              编辑
            </Button>
            <Button size="small" danger icon={<DeleteOutlined />} onClick={() => openDeleteHoliday(record)}>
              删除
            </Button>
          </Space>
        </RequiresPermission>
      ),
    },
  ];

  return (
    <div>
      <Title level={3}>行情品种管理</Title>
      <Tabs
        items={[
          {
            key: "symbols",
            label: "Symbol 主表",
            children: (
              <>
                <Card size="small" style={{ marginBottom: 16 }}>
                  <Form layout="inline" onFinish={handleSearch}>
                    <Form.Item name="category" label="类别">
                      <Select
                        allowClear
                        style={{ minWidth: 120 }}
                        options={Object.entries(CATEGORY_NAMES).map(([k, v]) => ({ value: Number(k), label: v }))}
                      />
                    </Form.Item>
                    <Form.Item name="marketCode" label="市场">
                      <Input placeholder="精确" allowClear />
                    </Form.Item>
                    <Form.Item name="lpCode" label="LP">
                      <Input placeholder="精确" allowClear />
                    </Form.Item>
                    <Form.Item name="status" label="状态">
                      <Select
                        allowClear
                        style={{ minWidth: 130 }}
                        options={[
                          { value: 1, label: "TRADING" },
                          { value: 2, label: "SUSPENDED" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item name="symbolLike" label="Symbol">
                      <Input placeholder="模糊" allowClear />
                    </Form.Item>
                    <Form.Item>
                      <Space>
                        <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                          搜索
                        </Button>
                        <Button icon={<ReloadOutlined />} onClick={() => setQuery({ page: 0, size: 20 })}>
                          重置
                        </Button>
                        <RequiresPermission code="symbol:source:create">
                          <Button icon={<PlusOutlined />} onClick={openCreateSource}>
                            新增 Symbol
                          </Button>
                        </RequiresPermission>
                      </Space>
                    </Form.Item>
                  </Form>
                </Card>
                <Table<SymbolListItem>
                  columns={columns}
                  dataSource={items}
                  rowKey="id"
                  loading={loading}
                  scroll={{ x: true }}
                  pagination={{
                    current: page + 1,
                    pageSize: size,
                    total,
                    showSizeChanger: true,
                    pageSizeOptions: [10, 20, 50, 100],
                    showTotal: (t) => `共 ${t} 条`,
                    onChange: (newPage, newSize) => setQuery({ ...query, page: newPage - 1, size: newSize }),
                  }}
                />
              </>
            ),
          },
          {
            key: "quote-mappings",
            label: (
              <span>
                <LinkOutlined /> 报价映射
              </span>
            ),
            children: (
              <>
                <Card size="small" style={{ marginBottom: 16 }}>
                  <Form layout="inline" onFinish={handleMappingSearch}>
                    <Form.Item name="platformSymbolLike" label="平台 Symbol">
                      <Input placeholder="模糊" allowClear />
                    </Form.Item>
                    <Form.Item name="sourceSymbolLike" label="源 Symbol">
                      <Input placeholder="模糊" allowClear />
                    </Form.Item>
                    <Form.Item name="sourceLpCode" label="源 LP">
                      <Input placeholder="精确" allowClear />
                    </Form.Item>
                    <Form.Item name="enabled" label="映射">
                      <Select
                        allowClear
                        style={{ minWidth: 110 }}
                        options={[
                          { value: 1, label: "启用" },
                          { value: 0, label: "停用" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item name="lpSubscribeEnabled" label="LP订阅">
                      <Select
                        allowClear
                        style={{ minWidth: 120 }}
                        options={[
                          { value: 1, label: "订阅" },
                          { value: 0, label: "不订阅" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item>
                      <Space>
                        <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                          搜索
                        </Button>
                        <Button icon={<ReloadOutlined />} onClick={() => setMappingQuery({ page: 0, size: 20 })}>
                          重置
                        </Button>
                        <RequiresPermission code="symbol:quote-mapping:update">
                          <Button icon={<PlusOutlined />} onClick={openCreateMapping}>
                            新建映射
                          </Button>
                        </RequiresPermission>
                      </Space>
                    </Form.Item>
                  </Form>
                </Card>
                <Table<SymbolQuoteMappingItem>
                  columns={mappingColumns}
                  dataSource={mappings}
                  rowKey="platformSymbol"
                  loading={mappingLoading}
                  scroll={{ x: true }}
                  pagination={{
                    current: mappingPage + 1,
                    pageSize: mappingSize,
                    total: mappingTotal,
                    showSizeChanger: true,
                    pageSizeOptions: [10, 20, 50, 100],
                    showTotal: (t) => `共 ${t} 条`,
                    onChange: (newPage, newSize) =>
                      setMappingQuery({ ...mappingQuery, page: newPage - 1, size: newSize }),
                  }}
                />
              </>
            ),
          },
          {
            key: "group-visibility",
            label: (
              <span>
                <EyeOutlined /> 组可见性
              </span>
            ),
            children: (
              <>
                <Card size="small" style={{ marginBottom: 16 }}>
                  <Form layout="inline" onFinish={handleVisibilitySearch}>
                    <Form.Item name="groupCodeLike" label="用户组">
                      <Input placeholder="模糊匹配" allowClear />
                    </Form.Item>
                    <Form.Item>
                      <Space>
                        <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                          搜索
                        </Button>
                        <Button icon={<ReloadOutlined />} onClick={() => setVisibilityQuery({ page: 0, size: 20 })}>
                          重置
                        </Button>
                        <RequiresPermission code="symbol:group-visibility:update">
                          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateVisibility}>
                            新建用户组
                          </Button>
                        </RequiresPermission>
                      </Space>
                    </Form.Item>
                  </Form>
                </Card>
                <Table<SymbolGroupVisibilityGroupedItem>
                  columns={visibilityColumns}
                  dataSource={visibilityItems}
                  rowKey={(record) => record.groupCode}
                  loading={visibilityLoading}
                  scroll={{ x: true }}
                  pagination={{
                    current: visibilityPage + 1,
                    pageSize: visibilitySize,
                    total: visibilityTotal,
                    showSizeChanger: true,
                    pageSizeOptions: [10, 20, 50, 100],
                    showTotal: (t) => `共 ${t} 个用户组`,
                    onChange: (newPage, newSize) =>
                      setVisibilityQuery({ ...visibilityQuery, page: newPage - 1, size: newSize }),
                  }}
                />
              </>
            ),
          },
          {
            key: "market-holidays",
            label: (
              <span>
                <CalendarOutlined /> 市场假期
              </span>
            ),
            children: (
              <>
                <Card size="small" style={{ marginBottom: 16 }}>
                  <Form layout="inline" onFinish={handleHolidaySearch}>
                    <Form.Item name="marketCode" label="市场">
                      <Select
                        allowClear
                        showSearch
                        style={{ minWidth: 160 }}
                        options={MARKET_CODE_OPTIONS.map((value) => ({ value, label: value }))}
                      />
                    </Form.Item>
                    <Form.Item>
                      <Space>
                        <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                          搜索
                        </Button>
                        <Button icon={<ReloadOutlined />} onClick={() => setHolidayQuery({ page: 0, size: 20 })}>
                          重置
                        </Button>
                        <RequiresPermission code="symbol:holiday:update">
                          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateHoliday}>
                            新增假期
                          </Button>
                        </RequiresPermission>
                      </Space>
                    </Form.Item>
                  </Form>
                </Card>
                <Table<MarketHolidayItem>
                  columns={holidayColumns}
                  dataSource={holidayItems}
                  rowKey="id"
                  loading={holidayLoading}
                  scroll={{ x: true }}
                  pagination={{
                    current: holidayPage + 1,
                    pageSize: holidaySize,
                    total: holidayTotal,
                    showSizeChanger: true,
                    pageSizeOptions: [10, 20, 50, 100],
                    showTotal: (t) => `共 ${t} 条`,
                    onChange: (newPage, newSize) =>
                      setHolidayQuery({ ...holidayQuery, page: newPage - 1, size: newSize }),
                  }}
                />
              </>
            ),
          },
        ]}
      />

      {/* 编辑 Drawer */}
      <Drawer
        title={editing ? `编辑 ${editing.symbol} 配置（高风险）` : ""}
        open={!!editing}
        width={520}
        onClose={() => setEditing(null)}
        extra={
          <Space>
            <Button onClick={() => setEditing(null)}>取消</Button>
            <Button type="primary" danger loading={editSubmitting} onClick={submitEdit}>
              确认保存
            </Button>
          </Space>
        }
      >
        {editing && (
          <Form<UpdateFormValues> layout="vertical" form={editForm}>
            <Alert
              type="warning"
              showIcon
              message="高风险：这里只编辑 LP 源 symbol 元数据；系统级类别 / 市场 / 精度与交易参数请到 报价映射 tab 编辑 mapping 配置。"
              style={{ marginBottom: 16 }}
            />
            <Form.Item name="category" label="类别" rules={[{ required: true }]}>
              <Select
                options={Object.entries(CATEGORY_NAMES).map(([k, v]) => ({ value: Number(k), label: v }))}
              />
            </Form.Item>
            <Form.Item name="marketCode" label="市场代码" rules={[{ required: true, max: 32 }]}>
              <Input />
            </Form.Item>
            <Form.Item
              name="pricePrecision"
              label="价格精度 (0-8)"
              rules={[{ required: true }, { type: "number", min: 0, max: 8 }]}
            >
              <InputNumber style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item
              name="qtyPrecision"
              label="数量精度 (0-8)"
              rules={[{ required: true }, { type: "number", min: 0, max: 8 }]}
            >
              <InputNumber style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item
              name="reason"
              label="操作原因（必填，≥ 10 字符）"
              rules={[{ required: true, min: 10, max: 500, message: "原因 10-500 字符" }]}
            >
              <Input.TextArea rows={3} showCount maxLength={500} />
            </Form.Item>
          </Form>
        )}
      </Drawer>

      {/* 新建 LP 源 Symbol Drawer */}
      <Drawer
        title="新增 LP 源 Symbol（高风险）"
        open={sourceCreateOpen}
        width={520}
        onClose={() => setSourceCreateOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setSourceCreateOpen(false)}>取消</Button>
            <Button type="primary" danger loading={sourceCreateSubmitting} onClick={submitCreateSource}>
              确认创建
            </Button>
          </Space>
        }
      >
        <Alert
          type="warning"
          showIcon
          message="这里只新增 LP 上游源 symbol。系统级可交易 Symbol、类别、市场、精度和交易参数仍在 报价映射 tab 配置。"
          style={{ marginBottom: 16 }}
        />
        <Form<SourceCreateFormValues> layout="vertical" form={sourceCreateForm}>
          <Form.Item name="lpCode" label="LP 代码" rules={[{ required: true, max: 32 }]}>
            <Input placeholder={DEFAULT_LP_CODE} />
          </Form.Item>
          <Form.Item name="symbol" label="源 Symbol" rules={[{ required: true, max: 32 }]}>
            <Input placeholder="如 AAPL.NAS / XAUUSD" />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="category" label="源类别" rules={[{ required: true }]}>
              <Select
                style={{ width: 220 }}
                options={Object.entries(CATEGORY_NAMES).map(([k, v]) => ({ value: Number(k), label: v }))}
              />
            </Form.Item>
            <Form.Item name="marketCode" label="源市场" rules={[{ required: true, max: 32 }]}>
              <Select
                showSearch
                style={{ width: 220 }}
                options={MARKET_CODE_OPTIONS.map((value) => ({ value, label: value }))}
              />
            </Form.Item>
          </Space>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="baseCurrency" label="基础币种" rules={[{ required: true, max: 16 }]}>
              <Input style={{ width: 220 }} />
            </Form.Item>
            <Form.Item name="quoteCurrency" label="计价币种" rules={[{ required: true, max: 16 }]}>
              <Input style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item
              name="pricePrecision"
              label="上游价格精度 (0-10)"
              rules={[{ required: true }, { type: "number", min: 0, max: 10 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
            <Form.Item
              name="qtyPrecision"
              label="上游数量精度 (0-10)"
              rules={[{ required: true }, { type: "number", min: 0, max: 10 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[{ required: true, min: 10, max: 500 }]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* Quote Mapping Drawer */}
      <Drawer
        title={mappingTarget ? `编辑映射 ${mappingTarget.platformSymbol}` : "新建报价映射（高风险）"}
        open={mappingDrawerOpen}
        width={560}
        onClose={() => {
          setMappingDrawerOpen(false);
          setMappingTarget(null);
        }}
        extra={
          <Space>
            <Button
              onClick={() => {
                setMappingDrawerOpen(false);
                setMappingTarget(null);
              }}
            >
              取消
            </Button>
            <Button type="primary" danger loading={mappingSubmitting} onClick={submitMapping}>
              确认保存
            </Button>
          </Space>
        }
      >
        <Alert
          type="warning"
          showIcon
          message="报价映射会影响平台展示 symbol 与上游 LP 源 symbol 的订阅和报价转换。"
          style={{ marginBottom: 16 }}
        />
        <Form<MappingFormValues> layout="vertical" form={mappingForm}>
          <Form.Item
            name="platformSymbol"
            label="平台 Symbol"
            rules={[{ required: true, max: 32 }]}
          >
            <Input disabled={!!mappingTarget} />
          </Form.Item>
          <Form.Item name="sourceProvider" label="源 Provider" rules={[{ max: 32 }]}>
            <Select options={[{ value: "LP", label: "LP" }]} />
          </Form.Item>
          <Form.Item name="sourceLpCode" hidden rules={[{ required: true, max: 32 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="sourceSymbol" hidden rules={[{ required: true, max: 32 }]}>
            <Input />
          </Form.Item>
          <Form.Item label="源 Symbol" required>
            <Select
              showSearch
              filterOption={false}
              loading={sourceOptionsLoading}
              value={sourceSelectValue}
              placeholder="从 t_symbol 选择"
              onSearch={(value) => void loadSourceOptions(value)}
              onFocus={() => {
                if (sourceOptions.length === 0) void loadSourceOptions();
              }}
              onSelect={(value) => {
                const source = parseSourceOptionValue(value);
                setSourceSelectValue(value);
                mappingForm.setFieldsValue({
                  sourceLpCode: source.lpCode,
                  sourceSymbol: source.symbol,
                });
                applySourceDefaults(source.symbol, source.lpCode);
              }}
              options={sourceOptions.map((item) => ({
                value: sourceOptionValue(item),
                label: `${item.lpCode} · ${item.symbol} · ${item.marketCode} · ${STATUS_NAMES[item.status]}`,
              }))}
            />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="category" label="系统类别" rules={[{ required: true }]}>
              <Select
                style={{ width: 220 }}
                options={Object.entries(CATEGORY_NAMES).map(([k, v]) => ({ value: Number(k), label: v }))}
              />
            </Form.Item>
            <Form.Item name="marketCode" label="系统市场" rules={[{ required: true, max: 32 }]}>
              <Select
                showSearch
                style={{ width: 220 }}
                options={MARKET_CODE_OPTIONS.map((value) => ({ value, label: value }))}
              />
            </Form.Item>
          </Space>
          <Form.Item
            name="priceMultiplier"
            label="价格乘数 (> 0)"
            rules={[{ required: true }, { type: "number", min: 0.00000001 }]}
          >
            <InputNumber style={{ width: "100%" }} step={0.0001} />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="bidAdjustment" label="Bid 绝对加点" rules={[{ required: true }]}>
              <InputNumber style={{ width: 220 }} step={0.0001} />
            </Form.Item>
            <Form.Item name="askAdjustment" label="Ask 绝对加点" rules={[{ required: true }]}>
              <InputNumber style={{ width: 220 }} step={0.0001} />
            </Form.Item>
          </Space>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="enabled" label="映射启用" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
            <Form.Item name="lpSubscribeEnabled" label="LP 订阅" valuePropName="checked">
              <Switch checkedChildren="订阅" unCheckedChildren="不订阅" />
            </Form.Item>
          </Space>
          {/* STAGE-2-SYMBOL-PARAMS-DOWNSHIFT：V11 从 t_symbol 下沉到 t_symbol_quote_mapping 的 8 字段 */}
          <Space style={{ width: "100%" }} size="large">
            <Form.Item
              name="maxLeverage"
              label="最大杠杆 (1-500)"
              rules={[{ required: true }, { type: "number", min: 1, max: 500 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
            <Form.Item
              name="takerFeeRate"
              label="手续费率 (0-0.05 即 0-5%)"
              rules={[{ required: true }, { type: "number", min: 0, max: 0.05 }]}
            >
              <InputNumber style={{ width: 220 }} step={0.0001} />
            </Form.Item>
          </Space>
          <Form.Item
            name="spread"
            label="点差 (≥ 0)"
            rules={[{ required: true }, { type: "number", min: 0 }]}
          >
            <InputNumber style={{ width: "100%" }} step={0.1} />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item
              name="minQty"
              label="最小数量 (≥ 0)"
              rules={[{ required: true }, { type: "number", min: 0 }]}
            >
              <InputNumber style={{ width: 220 }} step={0.01} />
            </Form.Item>
            <Form.Item
              name="maxQty"
              label="最大数量 (≥ 0)"
              rules={[{ required: true }, { type: "number", min: 0 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item
            name="minNotional"
            label="最小名义价值 (≥ 0)"
            rules={[{ required: true }, { type: "number", min: 0 }]}
          >
            <InputNumber style={{ width: "100%" }} />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item
              name="pricePrecision"
              label="系统价格精度 (0-10)"
              rules={[{ required: true }, { type: "number", min: 0, max: 10 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
            <Form.Item
              name="qtyPrecision"
              label="系统数量精度 (0-10)"
              rules={[{ required: true }, { type: "number", min: 0, max: 10 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[{ required: true, min: 10, max: 500 }]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* Group Visibility Drawer：Transfer 双列穿梭编辑 */}
      <Drawer
        title={
          visibilityTarget
            ? `编辑用户组 ${visibilityTarget.groupCode} 可见 Symbol（高风险）`
            : "新建用户组（高风险）"
        }
        open={visibilityDrawerOpen}
        width={840}
        onClose={() => {
          setVisibilityDrawerOpen(false);
          setVisibilityTarget(null);
        }}
        extra={
          <Space>
            <Button
              onClick={() => {
                setVisibilityDrawerOpen(false);
                setVisibilityTarget(null);
              }}
            >
              取消
            </Button>
            <Button type="primary" danger loading={visibilitySubmitting} onClick={submitVisibility}>
              确认保存
            </Button>
          </Space>
        }
      >
        <Alert
          type="warning"
          showIcon
          message="用户组可见性直接决定该组客户可看到和可订阅的 platform symbol。左侧是候选 mapping 池（已标注启用 / 停用），右侧是当前组可见集合。保存时按差集分别下发 bulk 启用 / 隐藏。"
          style={{ marginBottom: 16 }}
        />
        <Form<VisibilityGroupFormValues> layout="vertical" form={visibilityForm}>
          <Form.Item
            name="groupCode"
            label="用户组"
            rules={[{ required: true, max: 64 }]}
            extra={visibilityTarget ? "编辑模式下用户组不可改" : "新建模式：输入新用户组代码"}
          >
            <Input disabled={!!visibilityTarget} placeholder="如 default / vip-asia" />
          </Form.Item>
          <Form.Item
            label={`可见 Symbol 集合（已选 ${visibilityTargetKeys.length} / 候选 ${mappingPool.length}）`}
            required
          >
            <Transfer<MappingTransferRecord>
              dataSource={mappingPool}
              targetKeys={visibilityTargetKeys}
              onChange={(nextKeys) => setVisibilityTargetKeys(nextKeys as string[])}
              showSearch
              filterOption={(input, item) =>
                item.platformSymbol.toLowerCase().includes(input.toLowerCase()) ||
                item.sourceProvider.toLowerCase().includes(input.toLowerCase())
              }
              render={(item) => (
                <Space size={4}>
                  <code>{item.platformSymbol}</code>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {item.sourceProvider}
                  </Text>
                  {item.enabled === 1 ? (
                    <Tag color="green" style={{ marginInlineEnd: 0 }}>
                      启用
                    </Tag>
                  ) : (
                    <Tag color="red" style={{ marginInlineEnd: 0 }}>
                      停用
                    </Tag>
                  )}
                </Space>
              )}
              listStyle={{ width: 340, height: 420 }}
              titles={["候选 Symbol", "已选 Symbol"]}
              locale={{ itemUnit: "个", itemsUnit: "个", searchPlaceholder: "搜索 symbol / provider" }}
              disabled={mappingPoolLoading}
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[{ required: true, min: 10, max: 500 }]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* 暂停 / 恢复 reason 输入框 */}
      {pauseTarget && (
        <Modal
          open={!!pauseTarget}
          title={`${pauseTarget.status === 1 ? "暂停" : "恢复"} ${pauseTarget.symbol}（高风险）`}
          okText="确认"
          okType={pauseTarget.status === 1 ? "danger" : "primary"}
          okButtonProps={{ disabled: pauseReason.length < 10, loading: pauseSubmitting }}
          cancelButtonProps={{ disabled: pauseSubmitting }}
          onOk={submitPause}
          onCancel={() => {
            if (pauseSubmitting) return;
            setPauseTarget(null);
            setPauseReason("");
          }}
          maskClosable={false}
        >
          <Alert
            type="warning"
            showIcon
            message={
              pauseTarget.status === 1
                ? "暂停后该 symbol 不接受新订单；已有持仓不强平。"
                : "恢复后立即接受新订单。"
            }
            style={{ marginBottom: 16 }}
          />
          <Form layout="vertical">
            <Form.Item label="操作原因（必填，≥ 10 字符）" extra={`已输入 ${pauseReason.length} 字符`}>
              <Input.TextArea rows={3} value={pauseReason} onChange={(e) => setPauseReason(e.target.value)} />
            </Form.Item>
          </Form>
        </Modal>
      )}

      {/* Swap Rate Drawer */}
      <Drawer
        title={swapRateTarget ? `${swapRateTarget} 隔夜费率` : ""}
        open={!!swapRateTarget}
        width={640}
        onClose={() => setSwapRateTarget(null)}
      >
        {swapRateData && (
          <>
            <Card title="当前生效" size="small" style={{ marginBottom: 16 }}>
              {swapRateData.current ? (
                <Space size="large">
                  <span>
                    Long Rate: <Text strong>{swapRateData.current.longRate}</Text>
                  </span>
                  <span>
                    Short Rate: <Text strong>{swapRateData.current.shortRate}</Text>
                  </span>
                  <span>Rollover: {swapRateData.current.rolloverTime}</span>
                  <span>Effective: {swapRateData.current.effectiveFrom}</span>
                </Space>
              ) : (
                <Text type="secondary">尚无生效隔夜费率</Text>
              )}
            </Card>

            <Card title="历史费率（最近 5 条）" size="small" style={{ marginBottom: 16 }}>
              {swapRateData.history.length === 0 ? (
                <Text type="secondary">无历史</Text>
              ) : (
                <Table<SwapRateResponse["history"][number]>
                  size="small"
                  dataSource={swapRateData.history}
                  rowKey="id"
                  pagination={false}
                  columns={[
                    { title: "Long", dataIndex: "longRate" },
                    { title: "Short", dataIndex: "shortRate" },
                    { title: "Rollover", dataIndex: "rolloverTime" },
                    { title: "Effective", dataIndex: "effectiveFrom" },
                  ]}
                />
              )}
            </Card>

            <Card title="新增 / 更新隔夜费率（高风险）" size="small">
              <Alert
                type="warning"
                showIcon
                message="新行 effective_from 必须 ≥ 今天，且与历史 (symbol, effective_from) 不重复；长 / 短率绝对值上限 1%。"
                style={{ marginBottom: 12 }}
              />
              <Form<SwapRateFormValues> layout="vertical" form={swapRateForm} onFinish={submitSwapRate}>
                <Space style={{ width: "100%" }} size="large">
                  <Form.Item
                    name="longRate"
                    label="Long Rate (-0.01 ~ 0.01)"
                    rules={[{ required: true }, { type: "number", min: -0.01, max: 0.01 }]}
                  >
                    <InputNumber step={0.0001} style={{ width: 180 }} />
                  </Form.Item>
                  <Form.Item
                    name="shortRate"
                    label="Short Rate (-0.01 ~ 0.01)"
                    rules={[{ required: true }, { type: "number", min: -0.01, max: 0.01 }]}
                  >
                    <InputNumber step={0.0001} style={{ width: 180 }} />
                  </Form.Item>
                </Space>
                <Form.Item name="rolloverTime" label="结算时间 (UTC HH:mm:ss)">
                  <Input placeholder="22:00:00" />
                </Form.Item>
                <Form.Item
                  name="effectiveFrom"
                  label="生效日期 (≥ 今天)"
                  rules={[{ required: true }]}
                >
                  <DatePicker
                    style={{ width: "100%" }}
                    disabledDate={(d) => d.isBefore(dayjs().startOf("day"))}
                  />
                </Form.Item>
                <Form.Item
                  name="reason"
                  label="操作原因（必填，≥ 10 字符）"
                  rules={[{ required: true, min: 10, max: 500 }]}
                >
                  <Input.TextArea rows={3} showCount maxLength={500} />
                </Form.Item>
                <Form.Item>
                  <Button type="primary" danger htmlType="submit" loading={swapRateSubmitting}>
                    确认保存
                  </Button>
                </Form.Item>
              </Form>
            </Card>
          </>
        )}
      </Drawer>

      {/* Trading Hours Modal */}
      <Modal
        title={hoursTarget ? `${hoursTarget} 交易时段` : ""}
        open={!!hoursTarget}
        onCancel={() => setHoursTarget(null)}
        footer={[<Button key="close" onClick={() => setHoursTarget(null)}>关闭</Button>]}
        width={980}
      >
        {hoursData && (
          <>
            <Text type="secondary">市场代码：{hoursData.marketCode}</Text>
            <Card
              title="每周交易时段"
              size="small"
              style={{ marginTop: 12, marginBottom: 12 }}
              extra={
                <RequiresPermission code="symbol:trading-hours:update">
                  <Button size="small" icon={<PlusOutlined />} onClick={openCreateSession}>
                    新增时段
                  </Button>
                </RequiresPermission>
              }
            >
              {hoursData.sessions.length === 0 ? (
                <Text type="secondary">未配置</Text>
              ) : (
                <Table<TradingSessionItem>
                  size="small"
                  dataSource={hoursData.sessions}
                  rowKey="id"
                  pagination={false}
                  columns={[
                    { title: "星期", dataIndex: "dayOfWeek", render: (v: number) => DAY_OF_WEEK_NAMES[v] ?? v },
                    { title: "段", dataIndex: "sessionNo" },
                    { title: "开盘", dataIndex: "openTime" },
                    { title: "收盘", dataIndex: "closeTime" },
                    { title: "时区", dataIndex: "timezone" },
                    {
                      title: "启用",
                      dataIndex: "enabled",
                      render: (v: boolean) => (v ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>),
                    },
                    { title: "生效", dataIndex: "effectiveFrom" },
                    {
                      title: "失效",
                      dataIndex: "effectiveTo",
                      render: (v: string | null) => v ?? "长期",
                    },
                    {
                      title: "操作",
                      key: "actions",
                      render: (_, record: TradingSessionItem) => (
                        <RequiresPermission code="symbol:trading-hours:update">
                          <Space size={4}>
                            <Button size="small" icon={<EditOutlined />} onClick={() => openEditSession(record)}>
                              编辑
                            </Button>
                            <Button
                              size="small"
                              danger
                              icon={<DeleteOutlined />}
                              onClick={() => confirmDeleteSession(record)}
                            >
                              删除
                            </Button>
                          </Space>
                        </RequiresPermission>
                      ),
                    },
                  ]}
                />
              )}
            </Card>

            <Card
              title="特殊交易日"
              size="small"
              style={{ marginBottom: 12 }}
              extra={
                <RequiresPermission code="symbol:trading-hours:update">
                  <Button size="small" icon={<PlusOutlined />} onClick={openCreateException}>
                    新增例外
                  </Button>
                </RequiresPermission>
              }
            >
              {hoursData.exceptions.length === 0 ? (
                <Text type="secondary">未配置</Text>
              ) : (
                <Table<TradingHoursResponse["exceptions"][number]>
                  size="small"
                  dataSource={hoursData.exceptions}
                  rowKey="id"
                  pagination={false}
                  columns={[
                    { title: "日期", dataIndex: "tradeDate" },
                    {
                      title: "类型",
                      dataIndex: "exceptionType",
                      render: (t: number) => EXCEPTION_TYPE_NAMES[t] ?? t,
                    },
                    { title: "段", dataIndex: "sessionNo", render: (v: number | null) => v ?? "—" },
                    { title: "开盘", dataIndex: "openTime" },
                    { title: "收盘", dataIndex: "closeTime" },
                    { title: "原因", dataIndex: "reason", render: (v: string | null) => v ?? "—" },
                    {
                      title: "操作",
                      key: "actions",
                      render: (_, record: TradingHoursResponse["exceptions"][number]) => (
                        <RequiresPermission code="symbol:trading-hours:update">
                          <Space size={4}>
                            <Button size="small" icon={<EditOutlined />} onClick={() => openEditException(record)}>
                              编辑
                            </Button>
                            <Button
                              size="small"
                              danger
                              icon={<DeleteOutlined />}
                              onClick={() => confirmDeleteException(record)}
                            >
                              删除
                            </Button>
                          </Space>
                        </RequiresPermission>
                      ),
                    },
                  ]}
                />
              )}
            </Card>

            {hoursData.holidays.length > 0 && (
              <Card title={`${hoursData.marketCode} 市场节假日`} size="small">
                <Table<TradingHoursResponse["holidays"][number]>
                  size="small"
                  dataSource={hoursData.holidays}
                  rowKey="id"
                  pagination={false}
                  columns={[
                    { title: "日期", dataIndex: "holidayDate" },
                    { title: "名称", dataIndex: "holidayName" },
                    {
                      title: "类型",
                      dataIndex: "holidayType",
                      render: (t: number) =>
                        HOLIDAY_TYPE_NAMES[t] ?? t,
                    },
                    { title: "开盘", dataIndex: "openTime" },
                    { title: "收盘", dataIndex: "closeTime" },
                  ]}
                />
              </Card>
            )}
          </>
        )}
      </Modal>

      {/* Trading Session Drawer */}
      <Drawer
        title={sessionTarget ? "编辑交易时段（高风险）" : "新增交易时段（高风险）"}
        open={sessionDrawerOpen}
        width={520}
        onClose={() => {
          setSessionDrawerOpen(false);
          setSessionTarget(null);
        }}
        extra={
          <Space>
            <Button
              onClick={() => {
                setSessionDrawerOpen(false);
                setSessionTarget(null);
              }}
            >
              取消
            </Button>
            <Button type="primary" danger loading={sessionSubmitting} onClick={submitSession}>
              确认保存
            </Button>
          </Space>
        }
      >
        <Form<TradingSessionFormValues> layout="vertical" form={sessionForm}>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="dayOfWeek" label="星期" rules={[{ required: true }]}>
              <Select
                style={{ width: 220 }}
                options={Object.entries(DAY_OF_WEEK_NAMES).map(([value, label]) => ({
                  value: Number(value),
                  label,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="sessionNo"
              label="时段序号"
              rules={[{ required: true }, { type: "number", min: 1, max: 99 }]}
            >
              <InputNumber style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="openTime" label="开盘时间" rules={[{ required: true }]}>
              <TimePicker style={{ width: 220 }} format="HH:mm:ss" />
            </Form.Item>
            <Form.Item name="closeTime" label="收盘时间" rules={[{ required: true }]}>
              <TimePicker style={{ width: 220 }} format="HH:mm:ss" />
            </Form.Item>
          </Space>
          <Form.Item name="timezone" label="时区" rules={[{ required: true, max: 32 }]}>
            <Input placeholder="UTC / America/New_York" />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="enabled" label="启用" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
            <Form.Item name="effectiveFrom" label="生效日期" rules={[{ required: true }]}>
              <DatePicker style={{ width: 220 }} />
            </Form.Item>
          </Space>
          <Form.Item name="effectiveTo" label="失效日期">
            <DatePicker style={{ width: "100%" }} allowClear />
          </Form.Item>
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[{ required: true, min: 10, max: 500 }]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* Trading Exception Drawer */}
      <Drawer
        title={exceptionTarget ? "编辑特殊交易日（高风险）" : "新增特殊交易日（高风险）"}
        open={exceptionDrawerOpen}
        width={520}
        onClose={() => {
          setExceptionDrawerOpen(false);
          setExceptionTarget(null);
        }}
        extra={
          <Space>
            <Button
              onClick={() => {
                setExceptionDrawerOpen(false);
                setExceptionTarget(null);
              }}
            >
              取消
            </Button>
            <Button type="primary" danger loading={exceptionSubmitting} onClick={submitException}>
              确认保存
            </Button>
          </Space>
        }
      >
        <Form<TradingExceptionFormValues> layout="vertical" form={exceptionForm}>
          <Form.Item name="tradeDate" label="交易日期" rules={[{ required: true }]}>
            <DatePicker style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item name="exceptionType" label="类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 1, label: "全天休市" },
                { value: 2, label: "特殊时段" },
              ]}
            />
          </Form.Item>
          <Form.Item name="sessionNo" label="时段序号">
            <InputNumber style={{ width: "100%" }} min={1} max={99} />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="openTime" label="特殊开盘">
              <TimePicker style={{ width: 220 }} format="HH:mm:ss" />
            </Form.Item>
            <Form.Item name="closeTime" label="特殊收盘">
              <TimePicker style={{ width: 220 }} format="HH:mm:ss" />
            </Form.Item>
          </Space>
          <Form.Item name="timezone" label="时区" rules={[{ required: true, max: 32 }]}>
            <Input placeholder="UTC / America/New_York" />
          </Form.Item>
          <Form.Item name="ruleReason" label="规则原因（写入交易时段表，≤ 128 字符)" rules={[{ max: 128 }]}>
            <Input />
          </Form.Item>
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[{ required: true, min: 10, max: 500 }]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* Market Holiday Delete Modal */}
      <Modal
        title="删除市场假期（高风险）"
        open={holidayDeleteTarget !== null}
        onCancel={() => setHolidayDeleteTarget(null)}
        onOk={submitDeleteHoliday}
        confirmLoading={holidayDeleteSubmitting}
        okText="确认删除"
        okType="danger"
        destroyOnClose
        width={520}
      >
        {holidayDeleteTarget && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message={`即将删除 ${holidayDeleteTarget.marketCode} ${holidayDeleteTarget.holidayDate} 假期`}
            description="删除后该市场假期配置立即移除，并在事务提交后刷新交易时段快照。"
          />
        )}
        <Form<HolidayDeleteFormValues> form={holidayDeleteForm} layout="vertical">
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[
              { required: true, message: "必填" },
              { min: 10, message: "≥ 10 字符" },
              { max: 500, message: "≤ 500 字符" },
            ]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Market Holiday Drawer */}
      <Drawer
        title={holidayTarget ? "编辑市场假期（高风险）" : "新增市场假期（高风险）"}
        open={holidayDrawerOpen}
        width={520}
        onClose={() => {
          setHolidayDrawerOpen(false);
          setHolidayTarget(null);
        }}
        extra={
          <Space>
            <Button
              onClick={() => {
                setHolidayDrawerOpen(false);
                setHolidayTarget(null);
              }}
            >
              取消
            </Button>
            <Button type="primary" danger loading={holidaySubmitting} onClick={submitHoliday}>
              确认保存
            </Button>
          </Space>
        }
      >
        <Form<HolidayFormValues> layout="vertical" form={holidayForm}>
          <Form.Item name="marketCode" label="市场" rules={[{ required: true, max: 32 }]}>
            <Select showSearch options={MARKET_CODE_OPTIONS.map((value) => ({ value, label: value }))} />
          </Form.Item>
          <Form.Item name="holidayDate" label="日期" rules={[{ required: true }]}>
            <DatePicker style={{ width: "100%" }} />
          </Form.Item>
          <Form.Item name="holidayType" label="类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 1, label: "全天休市" },
                { value: 2, label: "提前收盘" },
                { value: 3, label: "晚开盘" },
              ]}
            />
          </Form.Item>
          <Space style={{ width: "100%" }} size="large">
            <Form.Item name="openTime" label="晚开盘时间">
              <TimePicker style={{ width: 220 }} format="HH:mm:ss" />
            </Form.Item>
            <Form.Item name="closeTime" label="提前收盘时间">
              <TimePicker style={{ width: 220 }} format="HH:mm:ss" />
            </Form.Item>
          </Space>
          <Form.Item name="timezone" label="时区" rules={[{ required: true, max: 32 }]}>
            <Input placeholder="UTC / America/New_York" />
          </Form.Item>
          <Form.Item name="holidayName" label="假期名称" rules={[{ required: true, max: 128 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="countryCode" label="地区代码" rules={[{ max: 16 }]}>
            <Input placeholder="US / HK / JP" />
          </Form.Item>
          <Form.Item
            name="reason"
            label="操作原因（必填，≥ 10 字符）"
            rules={[{ required: true, min: 10, max: 500 }]}
          >
            <Input.TextArea rows={3} showCount maxLength={500} />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
}
