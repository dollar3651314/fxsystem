package com.falconx.console.security;

import com.falconx.console.repository.AdminPermissionRepository;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;

/**
 * 启动时扫描所有 {@link RequiresPermission} 注解并同步到 {@code t_admin_permission} 字典。
 *
 * <p>扫描范围：所有被 {@link Controller} / {@link org.springframework.web.bind.annotation.RestController}
 * 标注的 bean 的所有 public 方法。
 *
 * <p>权限码解析：
 * <ul>
 *   <li>code = 注解的 {@code value}（必填）</li>
 *   <li>module = code 中 {@code ":"} 前部分</li>
 *   <li>action = {@code ":"} 后部分（可能含多段，如 {@code customer:balance:adjust} 的 action 是 {@code balance:adjust}）</li>
 *   <li>description = 注解的 {@code description}（可空）</li>
 * </ul>
 *
 * <p>清理策略（按 R6 TC-CONSOLE-038 注释）：本阶段不清理已删除的代码注解对应记录，
 * 仅追加新增；保留已有记录便于审计追溯（即使代码层删除了注解，历史角色权限关系仍可读）。
 *
 * <p>多实例并发安全：{@link AdminPermissionRepository#insertIfAbsent} 使用 {@code INSERT IGNORE}
 * 由 MySQL UNIQUE 索引保护，并发实例同时启动只会成功一次。
 */
@Component
public class AdminPermissionDictionaryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPermissionDictionaryInitializer.class);

    private final ApplicationContext applicationContext;
    private final AdminPermissionRepository adminPermissionRepository;

    public AdminPermissionDictionaryInitializer(ApplicationContext applicationContext,
                                                AdminPermissionRepository adminPermissionRepository) {
        this.applicationContext = applicationContext;
        this.adminPermissionRepository = adminPermissionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> scannedCodes = new HashSet<>();
        Map<String, Object> controllers = applicationContext.getBeansWithAnnotation(Controller.class);
        for (Object bean : controllers.values()) {
            Class<?> beanClass = AnnotationUtils.findAnnotation(bean.getClass(), Controller.class) != null
                    ? bean.getClass() : bean.getClass().getSuperclass();
            if (beanClass == null) {
                continue;
            }
            for (Method method : beanClass.getDeclaredMethods()) {
                RequiresPermission annotation = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
                if (annotation == null) {
                    continue;
                }
                String code = annotation.value();
                if (code == null || code.isBlank()) {
                    continue;
                }
                scannedCodes.add(code);
                if (registerPermission(code, annotation.description())) {
                    log.info("admin.permission.dictionary.inserted code={} description={}",
                            code, annotation.description());
                }
            }
        }
        log.info("admin.permission.dictionary.scan.completed total={}", scannedCodes.size());
    }

    private boolean registerPermission(String code, String description) {
        int colon = code.indexOf(':');
        if (colon <= 0 || colon == code.length() - 1) {
            log.warn("admin.permission.dictionary.code-invalid code={} reason=missing_colon_or_invalid_format", code);
            return false;
        }
        String module = code.substring(0, colon);
        String action = code.substring(colon + 1);
        return adminPermissionRepository.insertIfAbsent(code, module, action, description);
    }
}
