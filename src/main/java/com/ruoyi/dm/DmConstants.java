package com.ruoyi.dm;

/**
 * 达梦 DM8 数据库适配常量。
 *
 * <p>本类集中存放所有与达梦元数据相关的 SQL 片段与判定常量，
 * 目的是让使用方（若依 ruoyi-generator 的 Mapper XML）可以「只换 SQL、不改 Java」。</p>
 *
 * <p><b>背景</b>：若依默认的代码生成器表结构查询是为 MySQL 写的，
 * 直接切到达梦会出现三类问题（均已在本仓库的 probe 中用真实达梦实例复现）：</p>
 * <ol>
 *   <li>表注释读不到 —— 若依用 {@code table_comment} 列读，达梦需查 {@code USER_TAB_COMMENTS}；</li>
 *   <li>列注释读不到 —— 若依用 MySQL 的 {@code column_comment} 列，达梦需查 {@code USER_COL_COMMENTS}；</li>
 *   <li><b>自增列识别失效</b> —— 若依用 {@code DATA_DEFAULT like '%NEXTVAL%'} 判定，
 *       而达梦 IDENTITY 列在 {@code USER_TAB_COLUMNS.DATA_DEFAULT} 中是 {@code NULL}，
 *       导致所有自增列都被判为「非自增」，生成的代码插入时错误地带上主键字段。</li>
 * </ol>
 *
 * @author 李健熙
 */
public final class DmConstants {

    private DmConstants() {
    }

    /** 达梦数据库产品名（DatabaseMetaData.getDatabaseProductName() 返回值）。 */
    public static final String DM_PRODUCT_NAME = "DM DBMS";

    /** MyBatis VendorDatabaseIdProvider 中为达梦注册的 databaseId。 */
    public static final String DM_DATABASE_ID = "dm";

    /** PageHelper 的达梦方言标识。 */
    public static final String DM_HELPER_DIALECT = "dm";

    /**
     * 达梦自增列判定所依赖的字典表。
     *
     * <p>达梦 IDENTITY 列不建独立序列对象，其自增标记存放在内部数据字典
     * {@code SYSCOLUMNS.INFO2} 中：{@code INFO2 = 1} 表示该列为 IDENTITY 自增列，
     * 否则为 {@code 0}。</p>
     *
     * <p><b>注意</b>：{@code SYSCOLUMNS} 是达梦内部字典表，非 SQL 标准视图。
     * 若部署环境禁止访问 SYS 模式对象，请改用
     * {@link com.ruoyi.dm.jdbc.DmJdbcMetadataReader}（基于 JDBC 标准接口，实测同样可靠）。</p>
     */
    public static final String DM_SYSCOLUMNS = "SYSCOLUMNS";

    /** 达梦自增标记所在的字段名。 */
    public static final String DM_AUTO_INCREMENT_FLAG_COLUMN = "INFO2";

    /** {@code SYSCOLUMNS.INFO2} 取该值表示 IDENTITY 自增列。 */
    public static final int DM_AUTO_INCREMENT_FLAG_VALUE = 1;

    /**
     * 自增列判定的子查询片段（表名 → 自增标记表）。
     *
     * <p>用法：在查询用户表时，用当前行的表名与列名关联本子查询。
     * 参数占位符 {@code %s} 处填入「当前表名对应的列表达式」。</p>
     *
     * <pre>
     * select c.COLUMN_NAME,
     *        %s as is_increment
     *   from USER_TAB_COLUMNS c
     *  where c.TABLE_NAME = #{tableName}
     * </pre>
     *
     * @param tableNameExpression 表名的列表达式，例如 {@code c.TABLE_NAME}
     * @return 返回 1/0 的标量子查询 SQL
     */
    public static String autoIncrementSubQuery(String tableNameExpression) {
        return "nvl((select sc." + DM_AUTO_INCREMENT_FLAG_COLUMN
                + " from " + DM_SYSCOLUMNS + " sc"
                + " where sc.ID = (select so.ID from SYSOBJECTS so"
                + " where so.NAME = " + tableNameExpression + " and rownum <= 1)"
                + " and sc.NAME = c.COLUMN_NAME), 0)";
    }

    /**
     * 达梦列类型（{@code DATA_TYPE}）到「带长度/精度」的完整类型拼装规则。
     *
     * <p>达梦的 {@code USER_TAB_COLUMNS.DATA_TYPE} 只返回类型名（如 {@code VARCHAR}、
     * {@code DECIMAL}），长度与精度分别在 {@code DATA_LENGTH}、
     * {@code DATA_PRECISION}、{@code DATA_SCALE} 中，需要拼装后才能被若依的
     * {@code GenUtils.getColumnLength()} / {@code GenUtils.getDbType()} 正确解析。</p>
     *
     * <p>实测（达梦 8.0.0.0）：</p>
     * <pre>
     * BIGINT      → DATA_LENGTH=8,   DATA_PRECISION=NULL, DATA_SCALE=0  → BIGINT
     * VARCHAR(64) → DATA_LENGTH=64,  DATA_PRECISION=NULL, DATA_SCALE=0  → VARCHAR(64)
     * INT         → DATA_LENGTH=4,   DATA_PRECISION=NULL, DATA_SCALE=0  → INT
     * DECIMAL(10,2)→DATA_LENGTH=22,  DATA_PRECISION=10,   DATA_SCALE=2  → DECIMAL(10,2)
     * DATETIME    → DATA_LENGTH=8,   DATA_PRECISION=NULL, DATA_SCALE=6  → DATETIME
     * </pre>
     *
     * <p>即：只有「字符类型」需要拼长度，「数值类型」在有 {@code DATA_PRECISION}
     * 时需要拼 {@code (precision, scale)}，其余类型直接使用类型名。</p>
     */
    public static final String[] CHAR_TYPES_NEED_LENGTH = {
            "CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "BINARY", "VARBINARY"
    };

    /** 需要拼装 {@code (precision, scale)} 的数值类型。 */
    public static final String[] NUMBER_TYPES_NEED_PRECISION = {
            "NUMBER", "DECIMAL", "NUMERIC", "DEC", "FLOAT", "DOUBLE"
    };

    /** 达梦字符串拼接运算符（达梦同时兼容 {@code concat()} 与 {@code ||}）。 */
    public static final String DM_CONCAT_OPERATOR = "||";

    /** 达梦当前时间函数。 */
    public static final String DM_CURRENT_TIMESTAMP = "sysdate";

    /** 达梦分页方言在 MyBatis 中的 databaseId。 */
    public static final String DM_MYBATIS_DATABASE_ID = "dm";

    /** 判断类型是否需要拼装长度。 */
    public static boolean needLength(String dataType) {
        if (dataType == null) {
            return false;
        }
        String upper = dataType.trim().toUpperCase();
        for (String t : CHAR_TYPES_NEED_LENGTH) {
            if (t.equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /** 判断类型是否需要拼装精度。 */
    public static boolean needPrecision(String dataType) {
        if (dataType == null) {
            return false;
        }
        String upper = dataType.trim().toUpperCase();
        for (String t : NUMBER_TYPES_NEED_PRECISION) {
            if (t.equals(upper)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把达梦的「类型名 + 长度 + 精度 + 小数位」拼装成若依期望的完整类型串。
     *
     * @param dataType      达梦 {@code DATA_TYPE}，如 {@code VARCHAR}
     * @param dataLength    达梦 {@code DATA_LENGTH}，可为 null
     * @param dataPrecision 达梦 {@code DATA_PRECISION}，可为 null
     * @param dataScale     达梦 {@code DATA_SCALE}，可为 null
     * @return 如 {@code VARCHAR(64)}、{@code DECIMAL(10,2)}、{@code DATETIME}
     */
    public static String buildFullType(String dataType, Integer dataLength, Integer dataPrecision, Integer dataScale) {
        if (dataType == null || dataType.trim().isEmpty()) {
            return "";
        }
        String type = dataType.trim();
        if (needPrecision(type) && dataPrecision != null && dataPrecision > 0) {
            int scale = dataScale == null ? 0 : dataScale;
            return type + "(" + dataPrecision + "," + scale + ")";
        }
        if (needLength(type) && dataLength != null && dataLength > 0) {
            return type + "(" + dataLength + ")";
        }
        return type;
    }
}
