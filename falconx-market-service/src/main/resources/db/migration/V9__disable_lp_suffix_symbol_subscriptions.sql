-- Suffix variants ending in .p/.c/.f are not subscribed from the upstream LP and do not enter FalconX quote ingestion.
UPDATE t_symbol_quote_mapping
SET lp_subscribe_enabled = 0
WHERE LOWER(platform_symbol) LIKE '%.p'
   OR LOWER(platform_symbol) LIKE '%.c'
   OR LOWER(platform_symbol) LIKE '%.f'
   OR LOWER(source_symbol) LIKE '%.p'
   OR LOWER(source_symbol) LIKE '%.c'
   OR LOWER(source_symbol) LIKE '%.f';
