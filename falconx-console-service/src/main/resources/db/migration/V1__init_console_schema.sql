-- STAGE-1-CONSOLE-FOUNDATION：falconx_console schema 初始化（按 docs/architecture/管理端架构.md §2 RBAC 模型）。
-- 包含 6 张核心表 + 1 张审计表 + 默认超管 INSERT（首次登录强制改密）。

-- 1. 管理员账号
CREATE TABLE IF NOT EXISTS t_admin_user (
    id                BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    username          VARCHAR(64)     NOT NULL UNIQUE COMMENT '管理员登录用户名',
    password_hash     VARCHAR(255)    NOT NULL COMMENT 'BCrypt 加密的密码',
    real_name         VARCHAR(64)     NULL COMMENT '真实姓名',
    status            TINYINT         NOT NULL DEFAULT 1 COMMENT '1=ACTIVE,2=DISABLED',
    must_change_password TINYINT      NOT NULL DEFAULT 0 COMMENT '1=首次登录强制改密；改密后置 0',
    last_login_at     DATETIME(3)     NULL COMMENT '最后登录时间',
    last_login_ip     VARCHAR(64)     NULL COMMENT '最后登录 IP',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员账号表';

-- 2. 角色
CREATE TABLE IF NOT EXISTS t_admin_role (
    id                BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    code              VARCHAR(64)     NOT NULL UNIQUE COMMENT '角色 code（如 SUPER_ADMIN, FINANCE, KYC_REVIEWER）',
    name              VARCHAR(64)     NOT NULL COMMENT '角色显示名',
    description       TEXT            NULL COMMENT '角色描述',
    is_system         TINYINT         NOT NULL DEFAULT 0 COMMENT '1=系统内置（不可删，如 SUPER_ADMIN）',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台角色表';

-- 3. 权限点字典（由代码 @RequiresPermission 注解扫描生成 / 启动时同步）
CREATE TABLE IF NOT EXISTS t_admin_permission (
    id                BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    code              VARCHAR(128)    NOT NULL UNIQUE COMMENT '权限码（如 customer:view / customer:freeze）',
    module            VARCHAR(64)     NOT NULL COMMENT '所属模块（customer / withdraw / kyc / ...）',
    action            VARCHAR(64)     NOT NULL COMMENT '动作（view / create / freeze / approve / ...）',
    description       TEXT            NULL COMMENT '权限点描述',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台权限点字典';

-- 4. 菜单（树形结构）
CREATE TABLE IF NOT EXISTS t_admin_menu (
    id                BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    parent_id         BIGINT          NULL COMMENT '父菜单 ID（NULL = 顶级菜单）',
    code              VARCHAR(64)     NOT NULL UNIQUE COMMENT '菜单 code',
    name              VARCHAR(64)     NOT NULL COMMENT '菜单显示名',
    icon              VARCHAR(128)    NULL COMMENT '图标 code（前端按 code 渲染）',
    path              VARCHAR(255)    NULL COMMENT '前端路由 path（顶级菜单可为空）',
    permission_code   VARCHAR(128)    NULL COMMENT '该菜单需要的权限点（关联 t_admin_permission.code）',
    sort_order        INT             NOT NULL DEFAULT 0 COMMENT '同级菜单排序（小→大）',
    is_visible        TINYINT         NOT NULL DEFAULT 1 COMMENT '1=显示, 0=隐藏（隐藏菜单仍可路由访问）',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_parent_sort (parent_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台菜单表';

-- 5. 角色 ↔ 权限点
CREATE TABLE IF NOT EXISTS t_admin_role_permission (
    role_id           BIGINT          NOT NULL COMMENT '角色 ID（关联 t_admin_role.id）',
    permission_code   VARCHAR(128)    NOT NULL COMMENT '权限码（关联 t_admin_permission.code）',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色 ↔ 权限点关系表';

-- 6. 管理员 ↔ 角色
CREATE TABLE IF NOT EXISTS t_admin_user_role (
    user_id           BIGINT          NOT NULL COMMENT '管理员 ID（关联 t_admin_user.id）',
    role_id           BIGINT          NOT NULL COMMENT '角色 ID（关联 t_admin_role.id）',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员 ↔ 角色关系表';

-- 7. 管理员操作审计日志（所有管理操作必须写入；HIGH_RISK 月底由超管 review）
CREATE TABLE IF NOT EXISTS t_admin_operation_log (
    id                BIGINT          PRIMARY KEY COMMENT '主键 ID（雪花 ID）',
    admin_user_id     BIGINT          NOT NULL COMMENT '操作管理员 ID',
    permission_code   VARCHAR(128)    NOT NULL COMMENT '触发的权限点（标识操作类型）',
    target_type       VARCHAR(64)     NULL COMMENT '操作目标类型（user / withdraw / symbol / risk-action 等）',
    target_id         VARCHAR(128)    NULL COMMENT '操作目标 ID（字符串形式以兼容多种主键）',
    before_value      JSON            NULL COMMENT '操作前状态快照（如调余额前的 balance）',
    after_value       JSON            NULL COMMENT '操作后状态快照',
    risk_level        VARCHAR(16)     NOT NULL DEFAULT 'LOW' COMMENT 'LOW / MEDIUM / HIGH_RISK',
    ip                VARCHAR(64)     NULL COMMENT '操作来源 IP',
    user_agent        TEXT            NULL COMMENT '操作来源 User-Agent',
    occurred_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作发生时间',
    INDEX idx_admin_occurred (admin_user_id, occurred_at),
    INDEX idx_target (target_type, target_id),
    INDEX idx_risk_occurred (risk_level, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员操作审计日志表（HIGH_RISK 月底 review）';

-- 默认超管角色 INSERT（系统内置角色 id=1，是 SUPER_ADMIN 角色的固定标识）。
INSERT INTO t_admin_role (id, code, name, description, is_system) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '系统内置超管，拥有所有权限点，不可删除', 1);

-- 注 1：默认超管账号（t_admin_user + t_admin_user_role）由 console-service 启动时通过
--      DefaultSuperAdminInitializer (R9.4) 程序化创建：
--      - 检测无 username='superadmin' 用户时执行 INSERT
--      - 使用 BCrypt 加密 falconx.console.initial-super-admin.default-password 配置项
--      - 写入 must_change_password=1，首次登录强制改密
--      - 绑定 SUPER_ADMIN 角色 (role_id=1)
--      避免在 SQL 中硬编码假 BCrypt hash 导致用户无法登录。
--
-- 注 2：t_admin_permission（权限点字典）与 t_admin_role_permission（角色权限关系）的初始数据
--      由 R9.7 通过启动扫描 @RequiresPermission 注解自动同步生成；
--      SUPER_ADMIN 角色采用"通配匹配"策略（不在 t_admin_role_permission 中显式枚举），
--      服务层鉴权时若 role_code = SUPER_ADMIN 则直接放行（详见 R9.7 PermissionGuardService）。
