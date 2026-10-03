-- LP suffix variants ending in .p/.c/.f are not FalconX products.
-- Remove them from the LP symbol owner table and keep visibility/mapping data in sync.
DELETE FROM t_symbol_quote_mapping
WHERE LOWER(platform_symbol) LIKE '%.p'
   OR LOWER(platform_symbol) LIKE '%.c'
   OR LOWER(platform_symbol) LIKE '%.f'
   OR LOWER(source_symbol) LIKE '%.p'
   OR LOWER(source_symbol) LIKE '%.c'
   OR LOWER(source_symbol) LIKE '%.f';

DELETE FROM t_symbol_group_visibility
WHERE LOWER(symbol) LIKE '%.p'
   OR LOWER(symbol) LIKE '%.c'
   OR LOWER(symbol) LIKE '%.f';

DELETE FROM t_symbol
WHERE LOWER(symbol) LIKE '%.p'
   OR LOWER(symbol) LIKE '%.c'
   OR LOWER(symbol) LIKE '%.f';
