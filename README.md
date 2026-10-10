# ruoyi-dm-adapter

> 若依（RuoYi-Vue3）代码生成器 · 达梦 DM8 适配增强补丁
>
> 修复若依代码生成器在达梦数据库下「表结构识别不全、注释丢失、自增列判断失效」三类问题。

[![DM8](https://img.shields.io/badge/DM8-8.0.0.0-blue)]()
[![Driver](https://img.shields.io/badge/DmJdbcDriver18-8.1.3.140-blue)]()
[![Tests](https://img.shields.io/badge/tests-7%20passed-green)]()
[![License](https://img.shields.io/badge/license-MIT-green)]()

---

## 这个项目解决什么问题

若依的代码生成器（`ruoyi-generator`）默认只为 **MySQL** 设计，表结构查询直接读
`information_schema`。切到**达梦 DM8**（信创国产化场景的常见要求）后，
社区流传的改法能勉强跑起来，但存在三个**实测可复现**的缺陷：

| # | 问题 | 后果 | 本文档的修复 |
|---|------|------|-------------|
| 1 | 列注释丢失（`c.COLUMN_NAME as column_comment`） | 生成的实体类字段没有中文注释，前端表单标签是英文列名 | 关联 `USER_COL_COMMENTS` |
| 2 | **自增列识别 100% 失效** | 生成的代码把自增主键当普通字段，插入时报主键冲突 | 读 `SYSCOLUMNS.INFO2`（或 JDBC `IS_AUTOINCREMENT`） |
| 3 | 列类型丢失长度精度（`c.DATA_TYPE as column_type`） | `VARCHAR` 没有 `(64)`，`GenUtils` 推断的 `javaType`/`htmlType` 全错 | 按类型拼装 `(长度)` / `(精度,小数位)` |

第 2 点是最容易踩、也最难自己发现的坑，下面单独说明。

---

## 核心发现：达梦 IDENTITY 列为什么判断不出来

若依原版判定自增列的写法是：

```sql
(case when extra = 'auto_increment' then '1' else '0' end)   -- MySQL
```

社区改成达梦时的常见写法是沿用 Oracle 思路：

```sql
(case when c.DATA_DEFAULT is not null
       and c.DATA_DEFAULT like '%NEXTVAL%'
      then '1' else '0' end) as is_increment
```

**问题在于：达梦的 IDENTITY 列，`USER_TAB_COLUMNS.DATA_DEFAULT` 是 `NULL`。**

实测（达梦 8.0.0.0，表 `DM_PROBE_TEST`，`ID BIGINT IDENTITY(1,1)`）：

```
COLUMN_NAME | DATA_DEFAULT | 原判定结果
------------|--------------|-----------
ID          | NULL         | 0          ← 自增列被判成非自增
NAME        | NULL         | 0
AGE         | 18           | 0
AMOUNT      | NULL         | 0
CREATE_TIME | SYSDATE      | 0
```

也就是说，**这个表达式在达梦上对所有列恒为 0，自增识别完全失效**，
而且不会报错，属于静默错误——生成的代码看起来正常，插入时才炸。

### 自增标记的真实位置

达梦把 IDENTITY 标记存在内部数据字典 `SYSCOLUMNS.INFO2` 字段：

```
NAME    | COLID | TYPE$   | INFO1 | INFO2
--------|-------|---------|-------|------
ID      | 0     | BIGINT  | 0     | 1      ← INFO2 = 1 表示 IDENTITY 自增列
NAME    | 1     | VARCHAR | 0     | 0
```

所以正确写法是：

```sql
nvl((select sc.INFO2
       from SYSCOLUMNS sc
      where sc.ID = (select so.ID from SYSOBJECTS so
                      where so.NAME = c.TABLE_NAME and rownum <= 1)
        and sc.NAME = c.COLUMN_NAME), 0) as is_increment
```

> 注意两个坑：`SYSCOLUMNS` 关联的是 `SYSOBJECTS.ID`（**不是**表名），
> 而 `SYSCOLUMNS.NAME` 才是列名；`SYSCOLUMNS.COLID` 从 `0` 开始，与
> `USER_TAB_COLUMNS.COLUMN_ID` 从 `1` 开始不同。

### 更保险的替代方案

如果生产环境禁止应用账号访问 `SYS` 模式对象，可以改用 **JDBC 标准接口**，
它不依赖任何达梦私有系统表，且实测同样准确：

```java
// 实测：IDENTITY 列返回 YES，普通列返回 NO，对照组正确区分
try (ResultSet rs = conn.getMetaData().getColumns(null, "SYSDBA", "T_USER", "%")) {
    while (rs.next()) {
        boolean autoInc = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
    }
}
```

本仓库提供了完整实现：`com.ruoyi.dm.jdbc.DmJdbcMetadataReader`。

---

## 实测环境

| 项 | 值 |
|----|-----|
| 数据库 | 达梦 DM8（`getDatabaseProductVersion()` → `8.0.0.0`） |
| 驱动 | `DmJdbcDriver18` `8.1.3.140` |
| 目标框架 | RuoYi-Vue3 `3.9.0` / Spring Boot `2.5.15` / JDK 8 |
| 测试 | 7 个集成测试全部通过（连真实达梦执行） |

---

## 快速开始

### 方式一：只改 SQL（不动 Java 代码，5 分钟）

把 `sql/dm-adapter-patch.sql` 中 **【补丁 1】** 的 SQL 整段替换进
`ruoyi-generator/src/main/resources/mapper/generator/GenTableColumnMapper.xml`
的 `<select id="selectDbTableColumnsByName">` 语句。

同理用 **【补丁 4】** 替换 `GenTableMapper.xml` 的 `selectDbTableList` 系列
（达梦查 `USER_OBJECTS` + `USER_TAB_COMMENTS`）。

配置 `MyBatisConfig.databaseIdProvider()` 与 `pagehelper.helperDialect: dm` 见补丁 2、3。

### 方式二：用本仓库的 Java 实现（不依赖系统表视图）

```java
DmJdbcMetadataReader reader = new DmJdbcMetadataReader(connection, "SYSDBA");
List<ColumnMeta> columns = reader.listColumns("T_USER");   // 已含注释、自增、完整类型
List<String> pks = reader.listPrimaryKeys("T_USER");
List<Map<String, Object>> rows = DmGenTableMapper.toGenTableColumns(columns, pks);
// rows 可直接喂给若依 GenUtils.initColumnField()
```

### 自检

替换完 XML 后，用 `sql/dm-adapter-patch.sql` 末尾的**【自检脚本】**在达梦客户端
直接验证，预期：IDENTITY 列返回 `1`，普通列返回 `0`，注释正确显示。

---

## 运行测试

```bash
mvn test -Ddm.url=jdbc:dm://localhost:5236 -Ddm.user=SYSDBA -Ddm.pwd=你的密码
```

无达梦环境时测试会被 `Assume` **跳过**而非失败，不会阻塞 CI。

测试覆盖：

| 用例 | 验证内容 |
|------|---------|
| `testIdentityColumnIsDetected` | IDENTITY 列被识别为自增（核心回归） |
| `testColumnTypeCarriesLengthAndPrecision` | `VARCHAR(64)` / `DECIMAL(10,2)` 正确拼装 |
| `testColumnCommentIsReadFromDictionary` | 列注释来自字典表，无注释回退列名 |
| `testTableComment` | 表注释读取与回退 |
| `testConcatCompatibility` | `concat()` 与 `\|\|` 在达梦均可用 |
| `testSysColumnsInfo2SqlDetectsIdentity` | 纯 SQL 方案（SYSCOLUMNS.INFO2）可用 |
| `testNonIdentityTableIsNotMisdetected` | 非自增表不被误判 |

---

## 仓库结构

```
ruoyi-dm-adapter/
├── pom.xml
├── sql/
│   └── dm-adapter-patch.sql              # 可直接使用的 SQL 补丁（含自检脚本）
├── src/main/java/com/ruoyi/dm/
│   ├── DmConstants.java                  # 达梦适配常量与 SQL 片段生成
│   ├── DmGenTableMapper.java             # 表结构 → 若依 GenTable 映射
│   └── jdbc/
│       └── DmJdbcMetadataReader.java     # 基于 JDBC 标准接口的读取器
├── src/test/java/com/ruoyi/dm/
│   └── DmAdapterIntegrationTest.java     # 7 个集成测试（连真实达梦）
└── probe/                                # 元数据探针脚本
    ├── DmProbe.java ~ DmProbe4.java      # 逐步定位自增标记的证据链
    └── VerifyPatchSql.java               # 补丁 SQL 的自动化验证
```

`probe/` 目录保留了完整的排查过程——**结论不是猜的，是逐步实测出来的**，
每一步都能重跑复现。

---

## 兼容性说明

- **同时支持 MySQL 与达梦**：通过 MyBatis `_databaseId` 分支，
  一套代码两个库都能跑，不破坏原有 MySQL 部署。
- **人大金仓 / 高斯**：`DmJdbcMetadataReader` 基于 JDBC 标准接口，
  换库时基本无需改动（KingBase 与达梦的自增语义在 JDBC 层已统一）。
- **已知限制**：达梦 JDBC 驱动的 `getColumns().REMARKS` 为 `null`，
  列注释必须回查 `USER_COL_COMMENTS`，本仓库已处理。

---

## License

MIT
