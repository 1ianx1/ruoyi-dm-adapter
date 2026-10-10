import java.sql.*;

/**
 * 达梦 DM8 自增列判定 · 最终验证
 * 第三轮已锁定：SYSCOLUMNS.INFO2 = 1 表示 IDENTITY 自增列（COLID 从 0 开始）。
 * 且 SYSCOLUMNS.ID = SYSOBJECTS.ID（按表名关联可行）。
 * 本轮目标：多表交叉验证该结论，并确认可用于 MyBatis XML 的最终 SQL。
 */
public class DmProbe4 {

    static final String URL  = "jdbc:dm://localhost:5236";
    static final String USER = "SYSDBA";
    static final String PWD  = System.getProperty("dm.pwd", "");

    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD)) {
            System.out.println("=== 连接成功 ===\n");

            // 造三张表：IDENTITY / IDENTITY+复合键 / 无自增
            execQuiet(conn, "DROP TABLE V_INC1");
            execQuiet(conn, "DROP TABLE V_INC2");
            execQuiet(conn, "DROP TABLE V_NOINC");
            exec(conn, "CREATE TABLE V_INC1 (ID BIGINT IDENTITY(1,1) NOT NULL, NAME VARCHAR(64) NOT NULL, " +
                    "AGE INT DEFAULT 18, AMOUNT DECIMAL(10,2), CREATE_TIME DATETIME DEFAULT SYSDATE, " +
                    "CONSTRAINT PK_V_INC1 PRIMARY KEY (ID))");
            exec(conn, "CREATE TABLE V_INC2 (SEQ_NO INT IDENTITY(100,5) NOT NULL, CODE VARCHAR(32), CONSTRAINT PK_V_INC2 PRIMARY KEY (SEQ_NO))");
            exec(conn, "CREATE TABLE V_NOINC (ID BIGINT NOT NULL, NAME VARCHAR(64), CONSTRAINT PK_V_NOINC PRIMARY KEY (ID))");
            System.out.println("已建 V_INC1(IDENTITY(1,1)) / V_INC2(IDENTITY(100,5)) / V_NOINC(无自增)\n");

            // ==== 最终 SQL 候选：一次查出 注释+自增+完整类型 ====
            String FINAL_SQL =
                "select c.COLUMN_NAME as column_name, " +
                "  nvl(cc.COMMENTS, c.COLUMN_NAME) as column_comment, " +
                "  c.DATA_TYPE as raw_type, " +
                "  c.DATA_LENGTH, c.DATA_PRECISION, c.DATA_SCALE, " +
                "  nvl((select sc.INFO2 from SYSCOLUMNS sc " +
                "       where sc.ID = (select so.ID from SYSOBJECTS so where so.NAME = c.TABLE_NAME and rownum <= 1) " +
                "         and sc.NAME = c.COLUMN_NAME), 0) as is_increment, " +
                "  c.COLUMN_ID as sort, c.NULLABLE as is_required " +
                "from USER_TAB_COLUMNS c " +
                "left join USER_COL_COMMENTS cc on cc.TABLE_NAME = c.TABLE_NAME and cc.COLUMN_NAME = c.COLUMN_NAME " +
                "where c.TABLE_NAME = ? " +
                "order by c.COLUMN_ID";

            for (String t : new String[]{"V_INC1", "V_INC2", "V_NOINC"}) {
                System.out.println("---------- 最终 SQL 验证: " + t + " ----------");
                try (PreparedStatement ps = conn.prepareStatement(FINAL_SQL)) {
                    ps.setString(1, t);
                    try (ResultSet rs = ps.executeQuery()) {
                        System.out.println("COLUMN_NAME | COMMENT | RAW_TYPE | LEN | PREC | SCALE | IS_INCREMENT | SORT | REQUIRED");
                        while (rs.next()) {
                            System.out.println(rs.getString("column_name") + " | " +
                                    rs.getString("column_comment") + " | " +
                                    rs.getString("raw_type") + " | " +
                                    rs.getObject("DATA_LENGTH") + " | " +
                                    rs.getObject("DATA_PRECISION") + " | " +
                                    rs.getObject("DATA_SCALE") + " | " +
                                    rs.getObject("is_increment") + " | " +
                                    rs.getObject("sort") + " | " +
                                    rs.getObject("is_required"));
                        }
                    }
                } catch (Exception e) {
                    System.out.println("!! 失败: " + e.getMessage());
                }
                System.out.println();
            }

            // ==== 对照 JDBC 元数据 ====
            System.out.println("---------- 对照: JDBC IS_AUTOINCREMENT ----------");
            for (String t : new String[]{"V_INC1", "V_INC2", "V_NOINC"}) {
                try (ResultSet rs = conn.getMetaData().getColumns(null, "SYSDBA", t, "%")) {
                    while (rs.next()) {
                        System.out.println(t + "." + rs.getString("COLUMN_NAME") + " AUTOINC=" + rs.getString("IS_AUTOINCREMENT"));
                    }
                }
            }
            System.out.println();

            // ==== 达梦原生完整类型语法验证：能否直接拿 DATA_TYPE 拼出带长度的类型 ====
            System.out.println("---------- 达梦 DATA_TYPE 是否需要手工拼长度 ----------");
            probe(conn, "USER_TAB_COLUMNS 里的 DATA_TYPE 原始值（无长度）",
                    "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, CHAR_LENGTH " +
                    "FROM USER_TAB_COLUMNS WHERE TABLE_NAME='V_INC1' ORDER BY COLUMN_ID");
            probe(conn, "对比 USER_TAB_COLS 是否已含完整类型",
                    "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE FROM USER_TAB_COLS WHERE TABLE_NAME='V_INC1' ORDER BY COLUMN_ID");

            // ==== 序列验证：IDENTITY(100,5) 是否体现在 SYSCOLUMNS.INFO1 ====
            probe(conn, "V_INC2 的 SYSCOLUMNS 行（INFO1/INFO2 含义确认）",
                    "SELECT sc.NAME, sc.COLID, sc.TYPE$, sc.INFO1, sc.INFO2 FROM SYSCOLUMNS sc " +
                    "WHERE sc.ID = (SELECT ID FROM SYSOBJECTS WHERE NAME='V_INC2')");

            for (String t : new String[]{"V_INC1", "V_INC2", "V_NOINC"}) execQuiet(conn, "DROP TABLE " + t);
            System.out.println("\n=== 测试表已清理 ===");
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    static void execQuiet(Connection c, String sql) {
        try (Statement s = c.createStatement()) { s.execute(sql); } catch (Exception ignored) {}
    }
    static void probe(Connection c, String title, String sql) {
        System.out.println("[" + title + "]");
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
        } catch (Exception e) {
            System.out.println("!! 查询失败: " + e.getMessage());
        }
        System.out.println();
    }
}
