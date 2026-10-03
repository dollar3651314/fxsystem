package com.falconx.console.security;

import com.falconx.console.repository.AdminOperationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理操作审计 AOP 切面。
 *
 * <p>{@link AfterReturning} 在 {@link RequiresPermission @RequiresPermission} 方法成功返回后写入
 * {@code t_admin_operation_log}。失败的方法（抛异常）不会触发 AfterReturning，因此不写审计；
 * 这是有意设计：审计仅记录成功操作（失败操作由 {@code AdminGlobalExceptionHandler} 输出业务日志）。
 *
 * <p>字段映射：
 * <ul>
 *   <li>{@code admin_user_id} ← {@link AdminSecurityContextHolder#current()}</li>
 *   <li>{@code permission_code} ← 注解 {@code value}</li>
 *   <li>{@code target_type} ← 权限码 module 部分（如 {@code customer}）</li>
 *   <li>{@code target_id} ← method 参数中第一个 {@code @PathVariable} 值（取 {@code toString()}）</li>
 *   <li>{@code risk_level} ← {@link HighRiskPermissionRegistry#resolveRiskLevel(String)}</li>
 *   <li>{@code ip} ← {@code X-Forwarded-For} 第一段 / RemoteAddr</li>
 *   <li>{@code user_agent} ← {@code User-Agent} header</li>
 *   <li>{@code before_value / after_value}：业务方法通过 {@link AuditSnapshotHolder#set(Object, Object)}
 *       提供，aspect 用 Jackson 序列化为 JSON 写库；未设置则为 null（read-only 操作）</li>
 * </ul>
 *
 * <p>审计写入失败：try/catch 仅 log error，不影响业务流程返回（按 R6 TC-CONSOLE-051 决策）。
 */
@Aspect
@Component
public class OperationAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditAspect.class);
    private static final ObjectMapper AUDIT_JSON = JsonMapper.builder().build();

    private final AdminOperationLogRepository adminOperationLogRepository;

    public OperationAuditAspect(AdminOperationLogRepository adminOperationLogRepository) {
        this.adminOperationLogRepository = adminOperationLogRepository;
    }

    @AfterReturning("@annotation(com.falconx.console.security.RequiresPermission)")
    public void recordAudit(JoinPoint joinPoint) {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            // 未登录访问受保护接口本就会被 AOP 鉴权拦截；保险起见这里再校验一次
            return;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);
            if (annotation == null) {
                return;
            }
            String code = annotation.value();
            String module = extractModule(code);
            String targetId = extractFirstPathVariable(method, joinPoint.getArgs());
            String riskLevel = HighRiskPermissionRegistry.resolveRiskLevel(code);
            HttpServletRequest request = currentRequest();
            String ip = resolveIp(request);
            String userAgent = request == null ? null : request.getHeader("User-Agent");

            AuditSnapshotHolder.Snapshot snap = AuditSnapshotHolder.peek();
            String beforeJson = serializeOrNull(snap == null ? null : snap.before());
            String afterJson = serializeOrNull(snap == null ? null : snap.after());

            // Fallback：业务方法没显式调 AuditSnapshotHolder.set() 时，
            // 自动把 controller @RequestBody 入参作为 after 落库——
            // 至少能查到「变更请求里写了什么」。before 在此情况下仍为 null
            // （需要业务方法手动 setBefore；不在 fallback 责任内）。
            if (afterJson == null) {
                Object requestBody = extractRequestBody(method, joinPoint.getArgs());
                if (requestBody != null) {
                    afterJson = serializeOrNull(requestBody);
                }
            }

            adminOperationLogRepository.append(
                    principal.adminUserId(),
                    code,
                    module,
                    targetId,
                    beforeJson,
                    afterJson,
                    riskLevel,
                    ip,
                    userAgent,
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            log.debug("admin.audit.recorded userId={} code={} riskLevel={} targetId={} hasSnapshot={}",
                    principal.adminUserId(), code, riskLevel, targetId,
                    beforeJson != null || afterJson != null);
        } catch (Exception ex) {
            log.error("admin.audit.write.failure userId={} message={}",
                    principal.adminUserId(), ex.getMessage(), ex);
        } finally {
            // ThreadLocal 清理，避免线程池下一次请求脏读
            AuditSnapshotHolder.clear();
        }
    }

    private String serializeOrNull(Object value) {
        if (value == null) return null;
        try {
            return AUDIT_JSON.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("admin.audit.snapshot.serialize.failure type={} reason={}",
                    value.getClass().getSimpleName(), ex.toString());
            return null;
        }
    }

    private String extractModule(String code) {
        if (code == null) {
            return null;
        }
        int colon = code.indexOf(':');
        return colon > 0 ? code.substring(0, colon) : code;
    }

    private String extractFirstPathVariable(Method method, Object[] args) {
        if (method == null || args == null) {
            return null;
        }
        java.lang.annotation.Annotation[][] paramAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < paramAnnotations.length && i < args.length; i++) {
            for (java.lang.annotation.Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof PathVariable) {
                    Object value = args[i];
                    return value == null ? null : value.toString();
                }
            }
        }
        return null;
    }

    /**
     * 抓 controller 方法第一个 @RequestBody 入参，作为审计 fallback 的 after 快照。
     * 业务方法未手动 set holder 时使用——至少能在审计页看到「这次变更请求里写了什么」。
     */
    private Object extractRequestBody(Method method, Object[] args) {
        if (method == null || args == null) {
            return null;
        }
        java.lang.annotation.Annotation[][] paramAnnotations = method.getParameterAnnotations();
        for (int i = 0; i < paramAnnotations.length && i < args.length; i++) {
            for (java.lang.annotation.Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof RequestBody) {
                    return args[i];
                }
            }
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma == -1 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
