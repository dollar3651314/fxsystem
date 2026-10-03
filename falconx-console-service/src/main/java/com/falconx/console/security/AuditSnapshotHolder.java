package com.falconx.console.security;

/**
 * 审计前后值 ThreadLocal 容器。
 *
 * <p>业务层在执行变更前 / 后调用 {@link #set(Object, Object)}，
 * {@link OperationAuditAspect} 在 {@code @AfterReturning} 阶段读取并写入
 * {@code t_admin_operation_log.before_value / after_value}（JSON 序列化）。
 *
 * <p>必须在同一线程内（@RequestScope）使用，写完即 clear，避免污染线程池下一次请求。
 * aspect 已在 finally 里 clear，业务方法只管 set。
 *
 * <p>before / after 可以是任意 POJO / Map / Record，Jackson 能序列化即可。
 * 推荐：传一个紧凑的 LinkedHashMap，只放业务关键字段（避免泄露内部状态）。
 */
public final class AuditSnapshotHolder {

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private AuditSnapshotHolder() {}

    /**
     * 设置 before / after 快照。同一请求多次调用以最后一次为准（一般业务方法只调一次）。
     * before / after 任一可为 null（如 CREATE 操作 before 为 null）。
     */
    public static void set(Object before, Object after) {
        HOLDER.set(new Snapshot(before, after));
    }

    /** 仅设置 before（变更前调用），调用方需在变更后再 set 一次或 setAfter。 */
    public static void setBefore(Object before) {
        Snapshot cur = HOLDER.get();
        HOLDER.set(new Snapshot(before, cur == null ? null : cur.after()));
    }

    /** 仅设置 after（变更后调用），保留已 set 的 before。 */
    public static void setAfter(Object after) {
        Snapshot cur = HOLDER.get();
        HOLDER.set(new Snapshot(cur == null ? null : cur.before(), after));
    }

    static Snapshot peek() {
        return HOLDER.get();
    }

    /** AOP 在 @AfterReturning / finally 里调用，清理 ThreadLocal 防泄漏。 */
    static void clear() {
        HOLDER.remove();
    }

    record Snapshot(Object before, Object after) {}
}
