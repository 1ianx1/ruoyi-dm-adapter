import java.sql.*;

/**
 * 达梦 DM8 元数据探针
 * 目的：查清「自增列、列注释、数据类型、主键、唯一键」在达梦系统表里的真实存放方式。
 * 这是写适配代码前必须做的事——不能靠猜。
 */
public class DmProbe {

    // ===== 你需要修改这里的连接信息 =====
    static final String URL  = System.getProperty("dm.url",  "jdbc:dm://localhost:5236");
    static final String USER = System.getProperty("dm.user", "SYSDBA");
    static final String PWD  = System.getProperty("dm.pwd",  "SYSDBA001");
    // ===================================

    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD)) {
            System.out.println("=== 连接成功 ===");
            System.out.println("数据库产品: " + conn.getMetaData().getDatabaseProductName());
            System.out.println("数据库版本: " + conn.getMetaData().getDatabaseProductVersion());
            System.out.println("驱动版本  : " + conn.getMetaData().getDriverVersion());
            System.out.println("当前用户  : " + conn.getMetaData().getUserName());
            System.out.println("当前SCHEMA: " + conn.getSchema());
            System.out.println();

            // 先建一张测试表，覆盖各种典型情况
            System.out.println("=== 准备测试表 DM_PROBE_TEST ===");
            execQuiet(conn, "DROP TABLE DM_PROBE_TEST");
            execQuiet(conn, "DROP SEQUENCE SEQ_PROBE");
            exec(conn, "CREATE TABLE DM_PROBE_TEST (" +
                    "ID BIGINT IDENTITY(1,1) NOT NULL, " +
                    "NAME VARCHAR(64) NOT NULL, " +
                    "AGE INT DEFAULT 18, " +
                    "AMOUNT DECIMAL(10,2), " +
                    "REMARK VARCHAR(500), " +
                    "CREATE_TIME DATETIME DEFAULT SYSDATE, " +
                    "CONSTRAINT PK_DM_PROBE PRIMARY KEY (ID)" +
                    ")");
            exec(conn, "COMMENT ON TABLE DM_PROBE_TEST IS '探针测试表'");
            exec(conn, "COMMENT ON COLUMN DM_PROBE_TEST.ID IS '主键ID'");
            exec(conn, "COMMENT ON COLUMN DM_PROBE_TEST.NAME IS '姓名'");
            exec(conn, "COMMENT ON COLUMN DM_PROBE_TEST.AMOUNT IS '金额'");
            System.out.println("测试表已建好（含 IDENTITY 自增列、主键、注释）");
            System.out.println();

            // ---------- 探针1：USER_TAB_COLUMNS 全字段 ----------
            probe(conn, "探针1: USER_TAB_COLUMNS 完整字段（找自增/注释的线索）",
                    "SELECT * FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'DM_PROBE_TEST'");

            // ---------- 探针2：达梦专有的自增信息 ----------
            // 达梦把 IDENTITY 列信息放在 USER_TAB_COLUMNS 的 INFO2 字段（不同版本位置可能不同）
            probe(conn, "探针2: 达梦自增列特征（INFO2 字段 = IDENTITY 标记）",
                    "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, " +
                    "NULLABLE, DATA_DEFAULT, COLUMN_ID, INFO1, INFO2 " +
                    "FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'DM_PROBE_TEST' ORDER BY COLUMN_ID");

            // ---------- 探针3：列注释 ----------
            probe(conn, "探针3: 列注释（USER_COL_COMMENTS）",
                    "SELECT * FROM USER_COL_COMMENTS WHERE TABLE_NAME = 'DM_PROBE_TEST'");

            // ---------- 探针4：主键 ----------
            probe(conn, "探针4: 主键判定（USER_CONSTRAINTS + USER_CONS_COLUMNS）",
                    "SELECT uc.CONSTRAINT_NAME, uc.CONSTRAINT_TYPE, ucc.COLUMN_NAME, ucc.POSITION " +
                    "FROM USER_CONSTRAINTS uc JOIN USER_CONS_COLUMNS ucc " +
                    "ON uc.CONSTRAINT_NAME = ucc.CONSTRAINT_NAME " +
                    "WHERE uc.TABLE_NAME = 'DM_PROBE_TEST'");

            // ---------- 探针5：达梦是否有 IDENTITY 专用视图 ----------
            probe(conn, "探针5: 达梦自增专用视图 SYSCOLUMNS（查 IDENTITY 标志位）",
                    "SELECT NAME, ID, COLID, TYPE$, LENGTH$, SCALE$, NULLABLE$, DEFVAL, INFO2 " +
                    "FROM SYSCOLUMNS WHERE ID = (SELECT ID FROM SYSOBJECTS WHERE NAME = 'DM_PROBE_TEST')");

            // ---------- 探针6：SYS 库中是否有 IDENTITY 元数据 ----------
            probe(conn, "探针6: 达梦 IDENTITY 列专用视图（如果有的话）",
                    "SELECT NAME FROM SYSOBJECTS WHERE TYPE$ = 'SCHOBJ' AND SUBTYPE$ = 'STAB' AND NAME LIKE '%IDENT%'");

            // ---------- 探针7：全库搜索哪些视图含 IDENTITY 字样 ----------
            probe(conn, "探针7: 所有系统视图中名字含 IDENTITY 的",
                    "SELECT OWNER, VIEW_NAME FROM ALL_VIEWS WHERE VIEW_NAME LIKE '%IDENT%'");

            // ---------- 探针8：测试 USER_OBJECTS 表清单查询 ----------
            probe(conn, "探针8: USER_OBJECTS 查询表清单（若依原方案用的方式）",
                    "SELECT obj.OBJECT_NAME, obj.OBJECT_TYPE, obj.CREATED, obj.LAST_DDL_TIME " +
                    "FROM USER_OBJECTS obj WHERE obj.OBJECT_TYPE = 'TABLE' AND obj.OBJECT_NAME = 'DM_PROBE_TEST'");

            // ---------- 探针9：验证 concat / || 拼接 ----------
            probe(conn, "探针9: 字符串拼接兼容性（concat 函数 vs || 运算符）",
                    "SELECT concat('a','b') AS via_concat, 'a' || 'b' AS via_pipe FROM DUAL");

            // ---------- 探针10：验证若依原有 is_increment 判断是否会失效 ----------
            probe(conn, "探针10: 关键验证——原若依 is_increment 逻辑(DATA_DEFAULT like NEXTVAL)命中情况",
                    "SELECT COLUMN_NAME, DATA_DEFAULT, " +
                    "(CASE WHEN DATA_DEFAULT IS NOT NULL AND DATA_DEFAULT LIKE '%NEXTVAL%' THEN '1' ELSE '0' END) AS ruoyi_is_increment " +
                    "FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'DM_PROBE_TEST'");

            // ---------- 探针11：SYS.USER_TAB_COLUMNS 是否存在 ----------
            probe(conn, "探针11: 检查 SYS.USER_TAB_COLUMNS 与 USER_TAB_COLUMNS 是否等价",
                    "SELECT COUNT(*) AS cnt_from_sys_prefix FROM SYS.USER_TAB_COLUMNS WHERE TABLE_NAME = 'DM_PROBE_TEST'");

            // ---------- 探针12：查找 TDEPENDENCIES / 序列与列的关联 ----------
            probe(conn, "探针12: 达梦序列清单（自增列底层是序列）",
                    "SELECT NAME, ID, SUBTYPE$ FROM SYSOBJECTS WHERE SUBTYPE$ = 'SEQ' AND NAME LIKE '%PROBE%'");

            execQuiet(conn, "DROP TABLE DM_PROBE_TEST");
            System.out.println("\n=== 探针结束，测试表已清理 ===");
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    static void execQuiet(Connection c, String sql) {
        try (Statement s = c.createStatement()) { s.execute(sql); } catch (Exception ignored) {}
    }

    static void probe(Connection c, String title, String sql) {
        System.out.println("---------- " + title + " ----------");
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder head = new StringBuilder();
            for (int i = 1; i <= n; i++) head.append(md.getColumnLabel(i)).append(i < n ? " | " : "");
            System.out.println(head);
            int rows = 0;
            while (rs.next()) {
                rows++;
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    line.append(v == null ? "NULL" : String.valueOf(v)).append(i < n ? " | " : "");
                }
                System.out.println(line);
            }
            if (rows == 0) System.out.println("(无数据)");
            System.out.println("-> 行数: " + rows);
        } catch (Exception e) {
            System.out.println("!! 查询失败: " + e.getMessage());
        }
        System.out.println();
    }
}
