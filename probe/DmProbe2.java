import java.sql.*;

/**
 * 达梦 DM8 自增列（IDENTITY）元数据定位探针 · 第二轮
 * 第一轮已确认：USER_TAB_COLUMNS.DATA_DEFAULT 对 IDENTITY 列是 NULL，
 * 所以若依原逻辑 (DATA_DEFAULT like '%NEXTVAL%') 永远判不出自增。
 * 本轮目标：找到 IDENTITY 到底存在哪里。
 */
public class DmProbe2 {

    static final String URL  = "jdbc:dm://localhost:5236";
    static final String USER = "SYSDBA";
    static final String PWD  = System.getProperty("dm.pwd", "");

    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD)) {
            System.out.println("=== 连接成功 ===\n");

            execQuiet(conn, "DROP TABLE DM_ID_TEST");
            exec(conn, "CREATE TABLE DM_ID_TEST (" +
                    "ID BIGINT IDENTITY(1,1) NOT NULL, " +
                    "NAME VARCHAR(64), " +
                    "CONSTRAINT PK_DM_ID PRIMARY KEY (ID))");
            System.out.println("已建表 DM_ID_TEST（ID 为 IDENTITY 自增）\n");

            // ---- A: SYSCOLUMNS 全字段 ----
            probe(conn, "A: SYSCOLUMNS 完整结构（达梦底层列字典，自增信息通常在这里）",
                    "SELECT * FROM SYSCOLUMNS WHERE ID = (SELECT ID FROM SYSOBJECTS WHERE NAME='DM_ID_TEST' AND SUBTYPE$='STAB')");

            // ---- B: SYSCOLUMNS 的 INFO2 字段说明 ----
            probe(conn, "B: SYSCOLUMNS 关键字段（NAME/TYPE$/DEFVAL/INFO2）",
                    "SELECT NAME, COLID, TYPE$, LENGTH$, NULLABLE$, DEFVAL, INFO2 " +
                    "FROM SYSCOLUMNS WHERE ID = (SELECT ID FROM SYSOBJECTS WHERE NAME='DM_ID_TEST' AND SUBTYPE$='STAB')");

            // ---- C: 对比 —— 非自增表 vs 自增表 的 INFO2 ----
            execQuiet(conn, "DROP TABLE DM_NOID_TEST");
            exec(conn, "CREATE TABLE DM_NOID_TEST (ID BIGINT NOT NULL, NAME VARCHAR(64), CONSTRAINT PK_NOID PRIMARY KEY(ID))");
            probe(conn, "C: 对照组 —— 非自增表的 INFO2（应为空或不同值）",
                    "SELECT c.NAME, c.COLID, c.TYPE$, c.INFO2 FROM SYSCOLUMNS c " +
                    "WHERE c.ID = (SELECT ID FROM SYSOBJECTS WHERE NAME='DM_NOID_TEST' AND SUBTYPE$='STAB')");

            // ---- D: 达梦所有系统视图清单（找自增相关）----
            probe(conn, "D: 达梦全部系统视图（搜含 COL / IDENT / SEQ 的）",
                    "SELECT OWNER, VIEW_NAME FROM ALL_VIEWS WHERE OWNER IN ('SYS','SYSDBA') " +
                    "AND (VIEW_NAME LIKE '%COLUMN%' OR VIEW_NAME LIKE '%IDENT%' OR VIEW_NAME LIKE '%SEQ%' OR VIEW_NAME LIKE '%TAB%') " +
                    "ORDER BY VIEW_NAME");

            // ---- E: JDBC 标准接口 getGeneratedKeys / isAutoIncrement ----
            System.out.println("---------- E: JDBC 标准接口 DatabaseMetaData.getColumns() 的 IS_AUTOINCREMENT ----------");
            try (ResultSet rs = conn.getMetaData().getColumns(null, "SYSDBA", "DM_ID_TEST", "%")) {
                System.out.println("COLUMN_NAME | TYPE_NAME | IS_AUTOINCREMENT | COLUMN_DEF | REMARKS");
                while (rs.next()) {
                    System.out.println(rs.getString("COLUMN_NAME") + " | " +
                            rs.getString("TYPE_NAME") + " | " +
                            rs.getString("IS_AUTOINCREMENT") + " | " +
                            rs.getString("COLUMN_DEF") + " | " +
                            rs.getString("REMARKS"));
                }
            } catch (Exception e) {
                System.out.println("!! 失败: " + e.getMessage());
            }
            System.out.println();

            // ---- F: JDBC 标准接口 getPrimaryKeys ----
            System.out.println("---------- F: JDBC DatabaseMetaData.getPrimaryKeys() ----------");
            try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, "SYSDBA", "DM_ID_TEST")) {
                System.out.println("COLUMN_NAME | KEY_SEQ | PK_NAME");
                while (rs.next()) {
                    System.out.println(rs.getString("COLUMN_NAME") + " | " +
                            rs.getShort("KEY_SEQ") + " | " + rs.getString("PK_NAME"));
                }
            } catch (Exception e) {
                System.out.println("!! 失败: " + e.getMessage());
            }
            System.out.println();

            // ---- G: 达梦是否有 IDENTITY 专用系统表 ----
            probe(conn, "G: SYSOBJECTS 中所有对象类型统计",
                    "SELECT TYPE$, SUBTYPE$, COUNT(*) AS CNT FROM SYSOBJECTS GROUP BY TYPE$, SUBTYPE$ ORDER BY TYPE$, SUBTYPE$");

            execQuiet(conn, "DROP TABLE DM_ID_TEST");
            execQuiet(conn, "DROP TABLE DM_NOID_TEST");
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
