/**
 * gateway E2E 测试数据库清理支撑包。
 *
 * <p>约定如下：
 * <ul>
 *   <li>E2E 测试类必须显式标注 {@link com.falconx.gateway.support.E2ECleanupDatabases}</li>
 *   <li>测试数据库名必须定义在 `static final String` 字段中</li>
 *   <li>只有字段名以 `_DB_NAME` 结尾的常量才会被清理扩展识别</li>
 * </ul>
 */
package com.falconx.gateway.support;
