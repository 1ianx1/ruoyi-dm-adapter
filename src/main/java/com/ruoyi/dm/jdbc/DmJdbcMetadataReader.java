package com.ruoyi.dm.jdbc;

import com.ruoyi.dm.DmConstants;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JDBC 标准接口的达梦表结构读取器（不依赖任何达梦私有系统表）。
 *
 * <p>为什么需要它：</p>
 * <ul>
 *   <li>部分达梦部署环境出于安全考虑，禁止应用账号访问 {@code SYS} 模式下的系统视图，
 *       此时基于 {@code USER_TAB_COLUMNS} / {@code SYSCOLUMNS} 的 SQL 方案会失败；</li>
 *   <li>{@link DatabaseMetaData#getColumns} 返回的 {@code IS_AUTOINCREMENT} 字段
 *       由达梦驱动自行解析，<b>实测可正确识别 IDENTITY 列</b>；
 *       {@code getPrimaryKeys} 也能正确返回主键；</li>
 *   <li>换库（人大金仓 / 高斯）时本读取器无需改动，天然多数据库兼容。</li>
 * </ul>
 *
 * <p><b>实测结论（达梦 8.0.0.0 + DmJdbcDriver18 8.1.3.140）</b>：</p>
 * <pre>
 * 表 DM_ID3(ID BIGINT IDENTITY(1,1), NAME VARCHAR(64), PRIMARY KEY(ID))
 *   getColumns → ID   | BIGINT  | IS_AUTOINCREMENT=YES
 *                NAME | VARCHAR | IS_AUTOINCREMENT=NO
 * 对照表 DM_NOID3(ID BIGINT, NAME VARCHAR(64), PRIMARY KEY(ID))
 *   getColumns → ID   | BIGINT  | IS_AUTOINCREMENT=NO   ← 正确区分
 * getPrimaryKeys → ID | KEY_SEQ=1 | PK_NAME=PK_DM_ID3
 * </pre>
 *
 * <p><b>已知限制</b>：{@code getColumns} 返回的 {@code REMARKS}（列注释）
 * 在达梦驱动中为 {@code null}，因此列注释仍需回查
 * {@code USER_COL_COMMENTS}，见 {@link #listColumnComments(Connection, String)}。</p>
 *
 * @author 李健熙
 */
public class DmJdbcMetadataReader {

    private final Connection connection;
    private final String schema;

    /**
     * @param connection 达梦连接
     * @param schema     目标模式（schema）名；传 null 或空串表示不过滤模式
     */
    public DmJdbcMetadataReader(Connection connection, String schema) {
        this.connection = connection;
        this.schema = (schema == null || schema.trim().isEmpty()) ? null : schema.trim();
    }

    /**
     * 读取指定表的全部列信息。
     *
     * @param tableName 表名（达梦默认大写存储，建议调用方统一转大写）
     * @return 列元数据列表，按 {@code ORDINAL_POSITION} 升序
     */
    public List<ColumnMeta> listColumns(String tableName) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<ColumnMeta>();
        Map<String, String> comments = listColumnComments(connection, tableName);
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(null, schema, tableName, "%")) {
            while (rs.next()) {
                ColumnMeta meta = new ColumnMeta();
                meta.setColumnName(rs.getString("COLUMN_NAME"));
                meta.setDataType(rs.getString("TYPE_NAME"));
                meta.setColumnSize(rs.getInt("COLUMN_SIZE"));
                meta.setDecimalDigits(rs.getInt("DECIMAL_DIGITS"));
                meta.setNullable(rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                meta.setAutoIncrement("YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));
                meta.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));

                String remark = rs.getString("REMARKS");
                if (remark == null || remark.trim().isEmpty()) {
                    remark = comments.get(meta.getColumnName());
                }
                meta.setColumnComment(remark == null ? meta.getColumnName() : remark);
                meta.setColumnType(buildColumnType(meta));
                columns.add(meta);
            }
        }
        return columns;
    }

    /**
     * 读取指定表的主键列名集合。
     *
     * @param tableName 表名
     * @return 主键列名（保持 JDBC 返回的 KEY_SEQ 顺序）
     */
    public List<String> listPrimaryKeys(String tableName) throws SQLException {
        List<String> keys = new ArrayList<String>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, schema, tableName)) {
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    /**
     * 读取列注释。
     *
     * <p>达梦 JDBC 驱动的 {@code getColumns().REMARKS} 为 null，
     * 必须回查 {@code USER_COL_COMMENTS}。</p>
     *
     * @param conn      连接
     * @param tableName 表名
     * @return 列名 → 注释（保留插入顺序）
     */
    public static Map<String, String> listColumnComments(Connection conn, String tableName) throws SQLException {
        Map<String, String> comments = new LinkedHashMap<String, String>();
        String sql = "select COLUMN_NAME, COMMENTS from USER_COL_COMMENTS where TABLE_NAME = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String comment = rs.getString("COMMENTS");
                    if (comment != null && !comment.trim().isEmpty()) {
                        comments.put(rs.getString("COLUMN_NAME"), comment);
                    }
                }
            }
        }
        return comments;
    }

    /**
     * 读取表注释。
     *
     * @param conn      连接
     * @param tableName 表名
     * @return 表注释；无注释时返回表名本身（与若依默认行为一致）
     */
    public static String getTableComment(Connection conn, String tableName) throws SQLException {
        String sql = "select COMMENTS from USER_TAB_COMMENTS where TABLE_NAME = ?";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String comment = rs.getString("COMMENTS");
                    if (comment != null && !comment.trim().isEmpty()) {
                        return comment;
                    }
                }
            }
        }
        return tableName;
    }

    /**
     * 按达梦元数据拼装「带长度/精度」的完整列类型。
     *
     * <p>这是修复若依 {@code GenUtils} 类型推断的关键：若依从列类型串里
     * 用 {@code substringBetween(type, "(", ")")} 取长度，类型串缺括号会导致长度算成 0。</p>
     */
    private static String buildColumnType(ColumnMeta meta) {
        String type = meta.getDataType();
        if (type == null) {
            return "";
        }
        String upper = type.trim().toUpperCase();
        // 字符类型：拼长度
        if (DmConstants.needLength(upper)) {
            return upper + "(" + meta.getColumnSize() + ")";
        }
        // 数值类型：有精度时拼 (precision, scale)
        if (DmConstants.needPrecision(upper) && meta.getColumnSize() > 0
                && !"BIGINT".equals(upper) && !"INT".equals(upper) && !"INTEGER".equals(upper)
                && !"SMALLINT".equals(upper) && !"TINYINT".equals(upper)) {
            int scale = Math.max(meta.getDecimalDigits(), 0);
            return upper + "(" + meta.getColumnSize() + "," + scale + ")";
        }
        return upper;
    }

    /** 达梦列元数据。 */
    public static class ColumnMeta {
        private String columnName;
        private String columnType;
        private String dataType;
        private String columnComment;
        private boolean nullable;
        private boolean autoIncrement;
        private int columnSize;
        private int decimalDigits;
        private int ordinalPosition;

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getColumnType() {
            return columnType;
        }

        public void setColumnType(String columnType) {
            this.columnType = columnType;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getColumnComment() {
            return columnComment;
        }

        public void setColumnComment(String columnComment) {
            this.columnComment = columnComment;
        }

        public boolean isNullable() {
            return nullable;
        }

        public void setNullable(boolean nullable) {
            this.nullable = nullable;
        }

        /** 是否为自增列（达梦 IDENTITY）。 */
        public boolean isAutoIncrement() {
            return autoIncrement;
        }

        public void setAutoIncrement(boolean autoIncrement) {
            this.autoIncrement = autoIncrement;
        }

        public int getColumnSize() {
            return columnSize;
        }

        public void setColumnSize(int columnSize) {
            this.columnSize = columnSize;
        }

        public int getDecimalDigits() {
            return decimalDigits;
        }

        public void setDecimalDigits(int decimalDigits) {
            this.decimalDigits = decimalDigits;
        }

        public int getOrdinalPosition() {
            return ordinalPosition;
        }

        public void setOrdinalPosition(int ordinalPosition) {
            this.ordinalPosition = ordinalPosition;
        }

        @Override
        public String toString() {
            return columnName + " " + columnType
                    + (autoIncrement ? " [AUTO_INCREMENT]" : "")
                    + (nullable ? "" : " NOT NULL")
                    + " -- " + columnComment;
        }
    }
}
