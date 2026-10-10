import java.sql.*;

/**
 * 验证交付给用户的补丁 SQL 是否真的能在达梦上跑通。
 * 这段 SQL 就是 sql/dm-adapter-patch.sql 里【补丁1】的正文。
 */
public class VerifyPatchSql {

    static final String URL  = "jdbc:dm://localhost:5236";
    static final String USER = "SYSDBA";
    static final String PWD  = System.getProperty("dm.pwd", "");

    // 与交付文件完全一致的补丁 SQL（仅把 #{tableName} 换成 ?）
    static final String PATCH_SQL =
        "select c.COLUMN_NAME as column_name, " +
        "       (case when c.NULLABLE = 'N' then '1' else '0' end) as is_required, " +
        "       (case when pk.COLUMN_NAME is not null then '1' else '0' end) as is_pk, " +
        "       c.COLUMN_ID as sort, " +
        "       nvl(cc.COMMENTS, c.COLUMN_NAME) as column_comment, " +
        "       nvl((select sc.INFO2 from SYSCOLUMNS sc " +
        "             where sc.ID = (select so.ID from SYSOBJECTS so " +
        "                             where so.NAME = c.TABLE_NAME and rownum <= 1) " +
        "               and sc.NAME = c.COLUMN_NAME), 0) as is_increment, " +
        "       (case " +
        "            when c.DATA_TYPE in ('CHAR','VARCHAR','VARCHAR2','NCHAR','NVARCHAR','NVARCHAR2','BINARY','VARBINARY') " +
        "                 then c.DATA_TYPE || '(' || c.DATA_LENGTH || ')' " +
        "            when c.DATA_TYPE in ('NUMBER','DECIMAL','NUMERIC','DEC','FLOAT','DOUBLE') " +
        "                 and c.DATA_PRECISION is not null " +
        "                 then c.DATA_TYPE || '(' || c.DATA_PRECISION || ',' || nvl(c.DATA_SCALE, 0) || ')' " +
        "            else c.DATA_TYPE " +
        "        end) as column_type " +
        "  from USER_TAB_COLUMNS c " +
        "  left join USER_COL_COMMENTS cc " +
        "         on cc.TABLE_NAME = c.TABLE_NAME and cc.COLUMN_NAME = c.COLUMN_NAME " +
        "  left join (select ucc.TABLE_NAME, ucc.COLUMN_NAME " +
        "               from USER_CONSTRAINTS uc " +
        "               join USER_CONS_COLUMNS ucc " +
        "                 on ucc.CONSTRAINT_NAME = uc.CONSTRAINT_NAME " +
        "              where uc.CONSTRAINT_TYPE = 'P') pk " +
        "         on pk.TABLE_NAME = c.TABLE_NAME and pk.COLUMN_NAME = c.COLUMN_NAME " +
        " where c.TABLE_NAME = ? " +
        " order by c.COLUMN_ID";

    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD)) {
            System.out.println("=== 验证交付补丁 SQL（真实达梦执行） ===\n");

            drop(conn, "T_VERIFY_PATCH");
            exec(conn, "CREATE TABLE T_VERIFY_PATCH (" +
                    "ID BIGINT IDENTITY(1,1) NOT NULL, " +
                    "REAL_NAME VARCHAR(64) NOT NULL, " +
                    "PHONE VARCHAR(20), " +
                    "AGE INT DEFAULT 18, " +
                    "AMOUNT DECIMAL(10,2), " +
                    "REMARK VARCHAR(500), " +
                    "CREATE_TIME DATETIME DEFAULT SYSDATE, " +
                    "CONSTRAINT PK_T_VERIFY_PATCH PRIMARY KEY (ID))");
            exec(conn, "COMMENT ON COLUMN T_VERIFY_PATCH.ID IS '主键ID'");
            exec(conn, "COMMENT ON COLUMN T_VERIFY_PATCH.REAL_NAME IS '真实姓名'");
            exec(conn, "COMMENT ON COLUMN T_VERIFY_PATCH.PHONE IS '手机号'");
            exec(conn, "COMMENT ON COLUMN T_VERIFY_PATCH.AGE IS '年龄'");
            exec(conn, "COMMENT ON COLUMN T_VERIFY_PATCH.AMOUNT IS '金额'");

            System.out.println("测试表 T_VERIFY_PATCH 已建（含 IDENTITY 主键 + 注释 + 各种类型）\n");
            System.out.println("列名 | 必填 | 主键 | 序号 | 注释 | 自增 | 完整类型");
            System.out.println("-----|------|------|------|------|------|----------");

            try (PreparedStatement ps = conn.prepareStatement(PATCH_SQL)) {
                ps.setString(1, "T_VERIFY_PATCH");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        System.out.println(
                                rs.getString("column_name") + " | " +
                                rs.getString("is_required") + " | " +
                                rs.getString("is_pk") + " | " +
                                rs.getObject("sort") + " | " +
                                rs.getString("column_comment") + " | " +
                                rs.getObject("is_increment") + " | " +
                                rs.getString("column_type"));
                    }
                }
            }

            // ---- 断言 ----
            System.out.println("\n=== 断言检查 ===");
            boolean ok = true;
            try (PreparedStatement ps = conn.prepareStatement(PATCH_SQL)) {
                ps.setString(1, "T_VERIFY_PATCH");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String col = rs.getString("column_name");
                        String cmt = rs.getString("column_comment");
                        int inc = rs.getInt("is_increment");
                        String typ = rs.getString("column_type");
                        String pk = rs.getString("is_pk");

                        if ("ID".equals(col)) {
                            ok &= check("ID 自增=1", inc == 1);
                            ok &= check("ID 主键=1", "1".equals(pk));
                            ok &= check("ID 注释=主键ID", "主键ID".equals(cmt));
                            ok &= check("ID 类型=BIGINT", "BIGINT".equals(typ));
                        }
                        if ("REAL_NAME".equals(col)) {
                            ok &= check("REAL_NAME 注释=真实姓名", "真实姓名".equals(cmt));
                            ok &= check("REAL_NAME 类型=VARCHAR(64)", "VARCHAR(64)".equals(typ));
                            ok &= check("REAL_NAME 必填=1", "1".equals(rs.getString("is_required")));
                            ok &= check("REAL_NAME 非自增", inc == 0);
                        }
                        if ("AMOUNT".equals(col)) {
                            ok &= check("AMOUNT 类型=DECIMAL(10,2)", "DECIMAL(10,2)".equals(typ));
                            ok &= check("AMOUNT 注释=金额", "金额".equals(cmt));
                        }
                        if ("REMARK".equals(col)) {
                            ok &= check("REMARK 类型=VARCHAR(500)", "VARCHAR(500)".equals(typ));
                            ok &= check("REMARK 无注释时回退列名", "REMARK".equals(cmt));
                        }
                    }
                }
            }
            System.out.println(ok ? "\n>>> 全部断言通过：补丁 SQL 可用 <<<" : "\n>>> 存在断言失败 <<<");

            drop(conn, "T_VERIFY_PATCH");
            System.out.println("测试表已清理");
        }
    }

    static boolean check(String name, boolean cond) {
        System.out.println((cond ? "  [PASS] " : "  [FAIL] ") + name);
        return cond;
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    static void drop(Connection c, String t) {
        try (Statement s = c.createStatement()) { s.execute("DROP TABLE " + t); } catch (Exception ignored) {}
    }
}
