package com.ruoyi.dm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 达梦表结构 → 若依 GenTable / GenTableColumn 的映射器。
 *
 * <p>若依代码生成器的数据流是：</p>
 * <pre>
 * GenTableMapper.selectDbTableList()          查表清单
 *   → GenTableMapper.selectDbTableListByNames() / selectTableByName()
 *   → GenTableColumnMapper.selectDbTableColumnsByName()   查列
 *   → GenUtils.initColumnField()                按列类型推断 javaType / htmlType
 *   → VelocityUtils.prepareContext()           渲染 .vm 模板
 * </pre>
 *
 * <p>本类负责把 {@link com.ruoyi.dm.jdbc.DmJdbcMetadataReader.ColumnMeta}
 * 转换成一个「若依看得懂」的结构，重点修复三处：</p>
 * <ol>
 *   <li>{@code columnType} 补齐长度与精度，让 {@code GenUtils.getColumnLength()} 不再返回 0；</li>
 *   <li>{@code columnComment} 取自 {@code USER_COL_COMMENTS}，而非列名本身；</li>
 *   <li>{@code isIncrement} 取自 JDBC {@code IS_AUTOINCREMENT}，而非失效的 {@code NEXTVAL} 判定。</li>
 * </ol>
 *
 * @author 李健熙
 */
public class DmGenTableMapper {

    /**
     * 把达梦列元数据映射为若依风格的行数据。
     *
     * <p>返回的 Map key 与若依 {@code GenTableColumnMapper.xml} 中
     * {@code selectDbTableColumnsByName} 的 resultType 字段一一对应，
     * 因此可以直接被 {@code GenUtils.initColumnField()} 消费。</p>
     *
     * @param columns 达梦列元数据
     * @param primaryKeys 主键列名集合
     * @return 每列一个 Map，key 为若依字段名
     */
    public static List<Map<String, Object>> toGenTableColumns(
            List<com.ruoyi.dm.jdbc.DmJdbcMetadataReader.ColumnMeta> columns,
            List<String> primaryKeys) {

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int sort = 0;
        for (com.ruoyi.dm.jdbc.DmJdbcMetadataReader.ColumnMeta col : columns) {
            sort++;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("columnName", col.getColumnName());
            row.put("columnComment", col.getColumnComment());
            // 若依 GenUtils 会解析这里括号中的长度/精度
            row.put("columnType", col.getColumnType());
            row.put("javaType", null);   // 由 GenUtils.initColumnField 推断
            row.put("javaField", null);  // 由 GenUtils.initColumnField 推断
            row.put("isPk", primaryKeys.contains(col.getColumnName()) ? "1" : "0");
            // 主键列在达梦中通常是自增列，但两者需分别判断
            row.put("isIncrement", col.isAutoIncrement() ? "1" : "0");
            // 若依语义：is_required 为 "1" 表示必填（即数据库 NOT NULL）
            row.put("isRequired", col.isNullable() ? "0" : "1");
            row.put("isInsert", null);
            row.put("isEdit", null);
            row.put("isList", null);
            row.put("isQuery", null);
            row.put("queryType", null);
            row.put("htmlType", null);
            row.put("sort", sort);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 独立实现的若依 {@code GenUtils.getDbType()} 等价方法（含长度）。
     *
     * <p>若依原实现用 {@code indexOf("(") > 0} 截断，
     * 对 {@code DECIMAL(10,2)} 返回 {@code DECIMAL}，对 {@code VARCHAR} 返回 {@code VARCHAR}，
     * 本身没问题；问题在于上游把 {@code columnType} 传成了不带括号的 {@code VARCHAR}，
     * 导致 {@code getColumnLength()} 与小数位解析全部失效。此处一并给出可复用的工具方法。</p>
     *
     * @param columnType 完整列类型，如 {@code VARCHAR(64)}
     * @return 去掉长度后的类型名，如 {@code VARCHAR}
     */
    public static String getDbType(String columnType) {
        if (columnType == null || columnType.trim().isEmpty()) {
            return "";
        }
        int index = columnType.indexOf('(');
        return index > 0 ? columnType.substring(0, index) : columnType;
    }

    /**
     * 解析列类型中的长度/精度数值。
     *
     * @param columnType 完整列类型，如 {@code VARCHAR(64)} 或 {@code DECIMAL(10,2)}
     * @param index      0 表示取第一个数值（长度/精度），1 表示取第二个数值（小数位）
     * @return 解析结果；无法解析时返回 0（与若依行为一致）
     */
    public static int getColumnLength(String columnType, int index) {
        if (columnType == null) {
            return 0;
        }
        int left = columnType.indexOf('(');
        int right = columnType.indexOf(')');
        if (left < 0 || right < 0 || right <= left + 1) {
            return 0;
        }
        String inner = columnType.substring(left + 1, right);
        String[] parts = inner.split(",");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 按达梦类型推断若依的 javaType 与 htmlType（供不使用 GenUtils 的场景直接调用）。
     *
     * @param columnType 完整列类型，如 {@code VARCHAR(64)}
     * @return 长度为 2 的数组：[javaType, htmlType]
     */
    public static String[] resolveJavaAndHtmlType(String columnType) {
        String dbType = getDbType(columnType).toUpperCase();
        int length = getColumnLength(columnType, 0);
        if ("CHAR".equals(dbType) || "VARCHAR".equals(dbType) || "VARCHAR2".equals(dbType)
                || "NCHAR".equals(dbType) || "NVARCHAR".equals(dbType)) {
            if (length > 0 && length <= 500) {
                return new String[]{"String", "input"};
            }
            return new String[]{"String", "textarea"};
        }
        if ("CLOB".equals(dbType) || "TEXT".equals(dbType) || "LONGVARCHAR".equals(dbType)) {
            return new String[]{"String", "textarea"};
        }
        if ("DATE".equals(dbType) || "DATETIME".equals(dbType) || "TIMESTAMP".equals(dbType)
                || "TIME".equals(dbType)) {
            return new String[]{"Date", "datetime"};
        }
        if ("BIGINT".equals(dbType)) {
            return new String[]{"Long", "input"};
        }
        if ("INT".equals(dbType) || "INTEGER".equals(dbType) || "SMALLINT".equals(dbType)
                || "TINYINT".equals(dbType)) {
            return new String[]{"Long", "input"};
        }
        return new String[]{"BigDecimal", "input"};
    }
}
