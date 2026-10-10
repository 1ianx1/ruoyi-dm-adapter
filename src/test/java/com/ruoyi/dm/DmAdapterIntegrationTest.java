package com.ruoyi.dm;

import com.ruoyi.dm.jdbc.DmJdbcMetadataReader;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 达梦 DM8 适配集成测试 —— 需要本机可用的达梦实例。
 *
 * <p>连接参数通过系统属性覆盖，默认值对应本地开发环境：</p>
 * <pre>
 * mvn test -Ddm.url=jdbc:dm://localhost:5236 -Ddm.user=SYSDBA -Ddm.pwd=******
 * </pre>
 *
 * <p>若无法连接（例如 CI 环境没有达梦），全部测试会被 {@link Assume} 跳过而非失败，
 * 这样既能保证本地真实验证，又不会让 CI 变红。</p>
 *
 * @author 李健熙
 */
public class DmAdapterIntegrationTest {

    private static final String URL = System.getProperty("dm.url", "jdbc:dm://localhost:5236");
    private static final String USER = System.getProperty("dm.user", "SYSDBA");
    private static final String PWD = System.getProperty("dm.pwd", "");

    private static Connection conn;
    private static boolean available;

    @BeforeClass
    public static void setUp() {
        try {
            Class.forName("dm.jdbc.driver.DmDriver");
            conn = DriverManager.getConnection(URL, USER, PWD);
            available = true;
            System.out.println("[达梦集成测试] 已连接 " + URL + " 产品="
                    + conn.getMetaData().getDatabaseProductName()
                    + " 版本=" + conn.getMetaData().getDatabaseProductVersion());
        } catch (Throwable t) {
            available = false;
            System.out.println("[达梦集成测试] 跳过：无法连接达梦 —— " + t.getMessage());
        }
    }

    private void requireDb() {
        Assume.assumeTrue("本机无可用达梦实例，跳过集成测试", available);
    }

    // ---------------------------------------------------------------- 用例 1

    /**
     * 核心回归：达梦 IDENTITY 自增列必须被识别为自增。
     *
     * <p>若依默认判定 {@code DATA_DEFAULT like '%NEXTVAL%'} 在达梦上恒为 false，
     * 本用例即是该缺陷的回归保护。</p>
     */
    @Test
    public void testIdentityColumnIsDetected() throws SQLException {
        requireDb();
        String table = "T_DM_ADAPTER_INC";
        try {
            drop(table);
            exec("CREATE TABLE " + table + " ("
                    + "ID BIGINT IDENTITY(1,1) NOT NULL, "
                    + "NAME VARCHAR(64) NOT NULL, "
                    + "CONSTRAINT PK_" + table + " PRIMARY KEY (ID))");
            exec("COMMENT ON COLUMN " + table + ".ID IS '主键ID'");
            exec("COMMENT ON COLUMN " + table + ".NAME IS '姓名'");

            DmJdbcMetadataReader reader = new DmJdbcMetadataReader(conn, USER);
            List<DmJdbcMetadataReader.ColumnMeta> columns = reader.listColumns(table);

            assertEquals("应读到 2 个列", 2, columns.size());

            DmJdbcMetadataReader.ColumnMeta idCol = columns.get(0);
            assertEquals("ID", idCol.getColumnName());
            assertTrue("ID 列必须被识别为自增列（这是本插件的核心修复点）", idCol.isAutoIncrement());
            assertEquals("自增列类型应带长度", "BIGINT", idCol.getColumnType());

            DmJdbcMetadataReader.ColumnMeta nameCol = columns.get(1);
            assertFalse("NAME 列不是自增列", nameCol.isAutoIncrement());

            // 自增列在若依中的映射必须为 isIncrement=1
            List<String> pks = reader.listPrimaryKeys(table);
            assertTrue("ID 应被识别为主键", pks.contains("ID"));
            List<Map<String, Object>> rows = DmGenTableMapper.toGenTableColumns(columns, pks);
            assertEquals("1", rows.get(0).get("isIncrement"));
            assertEquals("1", rows.get(0).get("isPk"));
            assertEquals("0", rows.get(1).get("isIncrement"));
        } finally {
            drop(table);
        }
    }

    // ---------------------------------------------------------------- 用例 2

    /**
     * 列类型必须带长度/精度，否则若依 GenUtils 推断出的 javaType/htmlType 会错。
     */
    @Test
    public void testColumnTypeCarriesLengthAndPrecision() throws SQLException {
        requireDb();
        String table = "T_DM_ADAPTER_TYPE";
        try {
            drop(table);
            exec("CREATE TABLE " + table + " ("
                    + "ID BIGINT IDENTITY(1,1) NOT NULL, "
                    + "NAME VARCHAR(64) NOT NULL, "
                    + "REMARK VARCHAR(500), "
                    + "AGE INT DEFAULT 18, "
                    + "AMOUNT DECIMAL(10,2), "
                    + "CREATE_TIME DATETIME, "
                    + "CONSTRAINT PK_" + table + " PRIMARY KEY (ID))");

            DmJdbcMetadataReader reader = new DmJdbcMetadataReader(conn, USER);
            List<DmJdbcMetadataReader.ColumnMeta> columns = reader.listColumns(table);

            assertEquals("VARCHAR(64)", columnTypeOf(columns, "NAME"));
            assertEquals("VARCHAR(500)", columnTypeOf(columns, "REMARK"));
            assertEquals("DECIMAL(10,2)", columnTypeOf(columns, "AMOUNT"));
            assertEquals("BIGINT", columnTypeOf(columns, "ID"));
            assertEquals("DATETIME", columnTypeOf(columns, "CREATE_TIME"));

            // 验证若依 getColumnLength 等价逻辑能解析出长度
            assertEquals(64, DmGenTableMapper.getColumnLength(columnTypeOf(columns, "NAME"), 0));
            assertEquals(10, DmGenTableMapper.getColumnLength(columnTypeOf(columns, "AMOUNT"), 0));
            assertEquals(2, DmGenTableMapper.getColumnLength(columnTypeOf(columns, "AMOUNT"), 1));

            // 验证 javaType / htmlType 推断
            assertEquals("String", DmGenTableMapper.resolveJavaAndHtmlType("VARCHAR(64)")[0]);
            assertEquals("input", DmGenTableMapper.resolveJavaAndHtmlType("VARCHAR(64)")[1]);
            assertEquals("textarea", DmGenTableMapper.resolveJavaAndHtmlType("VARCHAR(2000)")[1]);
            assertEquals("Date", DmGenTableMapper.resolveJavaAndHtmlType("DATETIME")[0]);
            assertEquals("BigDecimal", DmGenTableMapper.resolveJavaAndHtmlType("DECIMAL(10,2)")[0]);
        } finally {
            drop(table);
        }
    }

    // ---------------------------------------------------------------- 用例 3

    /**
     * 列注释必须来自 USER_COL_COMMENTS，而不是列名本身。
     */
    @Test
    public void testColumnCommentIsReadFromDictionary() throws SQLException {
        requireDb();
        String table = "T_DM_ADAPTER_CMT";
        try {
            drop(table);
            exec("CREATE TABLE " + table + " ("
                    + "ID BIGINT IDENTITY(1,1) NOT NULL, "
                    + "USER_NAME VARCHAR(64), "
                    + "NO_COMMENT_COL VARCHAR(32), "
                    + "CONSTRAINT PK_" + table + " PRIMARY KEY (ID))");
            exec("COMMENT ON COLUMN " + table + ".ID IS '主键ID'");
            exec("COMMENT ON COLUMN " + table + ".USER_NAME IS '用户姓名'");

            DmJdbcMetadataReader reader = new DmJdbcMetadataReader(conn, USER);
            List<DmJdbcMetadataReader.ColumnMeta> columns = reader.listColumns(table);

            assertEquals("主键ID", commentOf(columns, "ID"));
            assertEquals("用户姓名", commentOf(columns, "USER_NAME"));
            // 无注释列应回退为列名（与若依默认行为一致）
            assertEquals("NO_COMMENT_COL", commentOf(columns, "NO_COMMENT_COL"));
        } finally {
            drop(table);
        }
    }

    // ---------------------------------------------------------------- 用例 4

    /**
     * 表注释读取。
     */
    @Test
    public void testTableComment() throws SQLException {
        requireDb();
        String table = "T_DM_ADAPTER_TCMT";
        try {
            drop(table);
            exec("CREATE TABLE " + table + " (ID BIGINT NOT NULL, CONSTRAINT PK_" + table + " PRIMARY KEY(ID))");
            exec("COMMENT ON TABLE " + table + " IS '适配测试表'");
            assertEquals("适配测试表", DmJdbcMetadataReader.getTableComment(conn, table));

            String noComment = "T_DM_ADAPTER_NOCMT";
            drop(noComment);
            exec("CREATE TABLE " + noComment + " (ID BIGINT NOT NULL, CONSTRAINT PK_" + noComment + " PRIMARY KEY(ID))");
            // 无注释时回退为表名
            assertEquals(noComment, DmJdbcMetadataReader.getTableComment(conn, noComment));
            drop(noComment);
        } finally {
            drop(table);
        }
    }

    // ---------------------------------------------------------------- 用例 5

    /**
     * 达梦 {@code concat()} 与 {@code ||} 均可用于字符串拼接（社区方案声称需改写，实测两种都行）。
     */
    @Test
    public void testConcatCompatibility() throws SQLException {
        requireDb();
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("select concat('a','b') as c1, 'a' || 'b' as c2")) {
            assertTrue(rs.next());
            assertEquals("ab", rs.getString("c1"));
            assertEquals("ab", rs.getString("c2"));
        }
    }

    // ---------------------------------------------------------------- 用例 6

    /**
     * 走 SYSCOLUMNS.INFO2 的纯 SQL 方案也必须能正确识别自增列。
     *
     * <p>验证 {@link DmConstants#autoIncrementSubQuery(String)} 拼出的 SQL 在真实达梦上可用，
     * 这是给 MyBatis XML 方案（不改 Java 代码）提供的路径。</p>
     */
    @Test
    public void testSysColumnsInfo2SqlDetectsIdentity() throws SQLException {
        requireDb();
        String table = "T_DM_ADAPTER_SQL";
        try {
            drop(table);
            exec("CREATE TABLE " + table + " ("
                    + "ID BIGINT IDENTITY(1,1) NOT NULL, "
                    + "NAME VARCHAR(64), "
                    + "CONSTRAINT PK_" + table + " PRIMARY KEY (ID))");

            String sql = "select c.COLUMN_NAME as column_name, "
                    + DmConstants.autoIncrementSubQuery("c.TABLE_NAME") + " as is_increment "
                    + "from USER_TAB_COLUMNS c where c.TABLE_NAME = '" + table + "' order by c.COLUMN_ID";

            Map<String, Integer> incMap = new java.util.LinkedHashMap<String, Integer>();
            try (Statement st = conn.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    incMap.put(rs.getString("column_name"), rs.getInt("is_increment"));
                }
            }
            assertEquals("应读到 2 个列", 2, incMap.size());
            assertEquals("ID 列经 SYSCOLUMNS.INFO2 判定应为自增", Integer.valueOf(1), incMap.get("ID"));
            assertEquals("NAME 列非自增", Integer.valueOf(0), incMap.get("NAME"));
        } finally {
            drop(table);
        }
    }

    // ---------------------------------------------------------------- 用例 7

    /**
     * 非自增表必须被正确判为「无自增列」，防止过度识别。
     */
    @Test
    public void testNonIdentityTableIsNotMisdetected() throws SQLException {
        requireDb();
        String table = "T_DM_ADAPTER_NOINC";
        try {
            drop(table);
            exec("CREATE TABLE " + table + " ("
                    + "ID BIGINT NOT NULL, "
                    + "NAME VARCHAR(64), "
                    + "CONSTRAINT PK_" + table + " PRIMARY KEY (ID))");

            DmJdbcMetadataReader reader = new DmJdbcMetadataReader(conn, USER);
            List<DmJdbcMetadataReader.ColumnMeta> columns = reader.listColumns(table);
            for (DmJdbcMetadataReader.ColumnMeta col : columns) {
                assertFalse("表中无 IDENTITY 列，不应误判：" + col.getColumnName(), col.isAutoIncrement());
            }
            assertNotNull(reader.listPrimaryKeys(table));
        } finally {
            drop(table);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String columnTypeOf(List<DmJdbcMetadataReader.ColumnMeta> columns, String name) {
        for (DmJdbcMetadataReader.ColumnMeta c : columns) {
            if (c.getColumnName().equals(name)) {
                return c.getColumnType();
            }
        }
        return null;
    }

    private static String commentOf(List<DmJdbcMetadataReader.ColumnMeta> columns, String name) {
        for (DmJdbcMetadataReader.ColumnMeta c : columns) {
            if (c.getColumnName().equals(name)) {
                return c.getColumnComment();
            }
        }
        return null;
    }

    private static void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void drop(String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE " + table);
        } catch (Exception ignored) {
            // 表不存在属正常
        }
    }
}
