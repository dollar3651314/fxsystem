export type SystemConfigItem = {
  id: number;
  configKey: string;
  configValue: string;
  valueType: "STRING" | "INT" | "LONG" | "DECIMAL" | "BOOL" | "DURATION" | "JSON";
  category: "RATE_LIMIT" | "SECURITY" | "TOKEN" | "AUTH" | "HEADER";
  scope: string;
  description: string | null;
  defaultValue: string | null;
  validationRegex: string | null;
  isSensitive: boolean;
  updatedBy: number | null;
  updatedAt: string;
};

export type SystemConfigListResponse = {
  items: SystemConfigItem[];
};

export type SystemConfigUpdateRequest = {
  newValue: string;
  reason?: string;
};

export type SystemConfigAuditItem = {
  id: number;
  configKey: string;
  oldValue: string | null;
  newValue: string | null;
  action: "CREATE" | "UPDATE" | "DELETE" | "RESET";
  operatorId: number;
  operatorEmail: string | null;
  clientIp: string | null;
  reason: string | null;
  createdAt: string;
};

export type SystemConfigAuditResponse = {
  items: SystemConfigAuditItem[];
};
