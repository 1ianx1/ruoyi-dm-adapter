import java.sql.*;

/**
 * 达梦 DM8 自增列（IDENTITY）元数据定位 · 第三轮（收口）
 * 前两轮结论：USER_TAB_COLUMNS.DATA_DEFAULT 对 IDENTITY 列为 NULL；
 * JDBC getMetaData().getColumns().IS_AUTOINCREMENT 能正确返回 YES。
 * 本轮目标：找出「纯 SQL 可查」的自增判定方式，供 MyBatis XML 使用。
 */
public class DmProbe3 {

    static final String URL  = "jdbc:dm://localhost:5236";
    static final String USER = "SYSDBA";
    static final String PWD  = System.getProperty("dm.pwd", "");

    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD)) {
            System.out.println("=== 连接成功 ===\n");

            execQuiet(conn, "DROP TABLE DM_ID3");
            exec(conn, "CREATE TABLE DM_ID3 (" +
                    "ID BIGINT IDENTITY(1,1) NOT NULL, " +
                    "NAME VARCHAR(64), " +
                    "CONSTRAINT PK_DM_ID3 PRIMARY KEY (ID))");
            execQuiet(conn, "DROP TABLE DM_NOID3");
            exec(conn, "CREATE TABLE DM_NOID3 (ID BIGINT NOT NULL, NAME VARCHAR(64), CONSTRAINT PK_NOID3 PRIMARY KEY(ID))");
            System.out.println("已建 IDENTITY 表 DM_ID3 与对照表 DM_NOID3\n");

            // ---- A: SYSCOLUMNS 用 NAME 关联（上一轮用 ID 失败）----
            probe(conn, "A1: SYSCOLUMNS 按表名关联（关键修正）",
                    "SELECT c.NAME, c.COLID, c.TYPE$, c.LENGTH$, c.NULLABLE$, c.DEFVAL, c.INFO1, c.INFO2 " +
                    "FROM SYSCOLUMNS c WHERE c.ID = (SELECT ID FROM SYSOBJECTS WHERE NAME='DM_ID3')");
            probe(conn, "A2: SYSCOLUMNS 全部列，不做 WHERE（看表里到底有什么）",
                    "SELECT * FROM SYSCOLUMNS WHERE ROWNUM <= 5");

            // ---- B: VSYSCOLUMNS（达梦 V$ 风格内部视图）----
            probe(conn, "B1: VSYSCOLUMNS 结构（前 3 行）",
                    "SELECT * FROM VSYSCOLUMNS WHERE ROWNUM <= 3");
            probe(conn, "B2: VSYSCOLUMNS 中 DM_ID3 的列（找自增标志）",
                    "SELECT * FROM VSYSCOLUMNS WHERE NAME IN ('DM_ID3','DM_NOID3') OR ID IN (SELECT ID FROM SYSOBJECTS WHERE NAME IN ('DM_ID3','DM_NOID3'))");

            // ---- C: SYSCOLUMNS 与 VSYSCOLUMNS 的列名清单 ----
            System.out.println("---------- C: SYSCOLUMNS / VSYSCOLUMNS 的列名清单 ----------");
            for (String v : new String[]{"SYSCOLUMNS", "VSYSCOLUMNS"}) {
                try (Statement s = conn.createStatement();
                     ResultSet rs = s.executeQuery("SELECT * FROM " + v + " WHERE ROWNUM <= 1")) {
                    ResultSetMetaData md = rs.getMetaData();
                    StringBuilder sb = new StringBuilder(v + " -> ");
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        sb.append(md.getColumnLabel(i));
                        if (i < md.getColumnCount()) sb.append(", ");
                    }
                    System.out.println(sb);
                } catch (Exception e) {
                    System.out.println(v + " -> 失败: " + e.getMessage());
                }
            }
            System.out.println();

            // ---- D: 用 JDBC 元数据交叉验证（对照组）----
            System.out.println("---------- D: JDBC IS_AUTOINCREMENT 对照组 ----------");
            for (String t : new String[]{"DM_ID3", "DM_NOID3"}) {
                try (ResultSet rs = conn.getMetaData().getColumns(null, "SYSDBA", t, "%")) {
                    while (rs.next()) {
                        System.out.println(t + "." + rs.getString("COLUMN_NAME")
                                + " | " + rs.getString("TYPE_NAME")
                                + " | AUTOINC=" + rs.getString("IS_AUTOINCREMENT"));
                    }
                }
            }
            System.out.println();

            // ---- E: 达梦IDENTITY列的建表DDL能否反查（DBMS_METADATA）----
            probe(conn, "E: 能否从 DDL 反查（达梦是否支持类似 DBMS_METADATA）",
                    "SELECT COUNT(*) AS CNT FROM ALL_OBJECTS WHERE OBJECT_NAME LIKE '%METADATA%'");

            execQuiet(conn, "DROP TABLE DM_ID3");
            execQuiet(conn, "DROP TABLE DM_NOID3");
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
                    String sv = v == null ? "NULL" : String.valueOf(v);
                    if (sv.length() > 60) sv = sv.substring(0, 60) + "...";
                    line.append(sv).append(i < n ? " | " : "");
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
