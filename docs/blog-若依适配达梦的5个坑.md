# 若依代码生成器适配达梦 DM8 的 5 个坑（附实测证据）

> 我在做烟台市森林防火政务项目时，负责把基于 RuoYi-Vue3 的系统从 MySQL 迁到达梦 DM8。
> 网上能找到的改法能跑，但**生成出来的代码是错的**——而且不报错。
> 这篇文章把 5 个坑和证据都摆出来，附可复现的排查脚本。

环境：达梦 DM8 `8.0.0.0` · `DmJdbcDriver18` `8.1.3.140` · RuoYi-Vue3 `3.9.0` · JDK 8

---

## 背景：若依代码生成器的数据流

理解坑之前，先看若依生成代码时到底做了什么：

```
GenTableMapper.selectDbTableList()              ① 查表清单
  → GenTableColumnMapper.selectDbTableColumnsByName()  ② 查列信息
  → GenUtils.initColumnField()                  ③ 按列类型推断 javaType / htmlType
  → VelocityUtils.prepareContext()              ④ 渲染 .vm 模板
```

**坑全在第 ②③ 步之间**：第 ② 步返回的 `column_type`、`column_comment`、`is_increment`
三个字段一旦不准，第 ③ 步的判断就会静默跑偏，最后生成一份「能编译、能启动、一插入就炸」的代码。

若依原版是为 MySQL 写的，第 ② 步直接查 `information_schema.columns`：

```sql
select column_name,
       (case when (is_nullable = 'no' && column_key != 'PRI') then '1' else '0' end) as is_required,
       (case when column_key = 'PRI' then '1' else '0' end) as is_pk,
       ordinal_position as sort,
       column_comment,
       (case when extra = 'auto_increment' then '1' else '0' end) as is_increment,
       column_type
from information_schema.columns
where table_schema = (select database()) and table_name = (#{tableName})
order by ordinal_position
```

达梦没有 `information_schema`，所以要换。下面是我踩过的 5 个坑。

---

## 坑 1：自增列判断恒为 false（最隐蔽、最致命）

### 现象

代码生成器生成的实体类，主键字段**没有**被标记为自增：

```java
/** 主键ID */
@Excel(name = "主键ID")
private Long id;
```

对应生成的 Mapper XML 里，`insert` 语句带上了主键列：

```xml
<insert id="insertUser" useGeneratedKeys="true" keyProperty="id">
    insert into t_user
    <trim prefix="(" suffix=")" suffixOverrides=",">
        <if test="id != null">id,</if>          <!-- ← 本不该出现 -->
        ...
```

结果：**新增记录时主键传 `null`，直接插入报错**；或者主键冲突。

### 原因

达梦的 IDENTITY 列，`USER_TAB_COLUMNS.DATA_DEFAULT` **是 `NULL`**。

我用真实达梦建了张表验证：

```sql
CREATE TABLE DM_PROBE_TEST (
    ID   BIGINT IDENTITY(1,1) NOT NULL,
    NAME VARCHAR(64) NOT NULL,
    AGE  INT DEFAULT 18,
    CONSTRAINT PK_DM_PROBE PRIMARY KEY (ID)
);
```

然后套用社区流传的判定逻辑：

```sql
select COLUMN_NAME,
       DATA_DEFAULT,
       (case when DATA_DEFAULT is not null
              and DATA_DEFAULT like '%NEXTVAL%'
             then 1 else 0 end) as RUOYI_IS_INCREMENT
from USER_TAB_COLUMNS
where TABLE_NAME = 'DM_PROBE_TEST'
order by COLUMN_ID;
```

**实测结果**：

```
COLUMN_NAME | DATA_DEFAULT | RUOYI_IS_INCREMENT
------------|--------------|-------------------
ID          | NULL         | 0        ← 自增列，判成了 0
NAME        | NULL         | 0
AGE         | 18           | 0
AMOUNT      | NULL         | 0
CREATE_TIME | SYSDATE      | 0
```

**所有列恒为 0。** 这个表达式在达梦上没有任何区分能力。

> 为什么社区会这么写？因为 Oracle 的 `USER_TAB_COLUMNS.DATA_DEFAULT`
> 对 identity 列会显示 `"SCHEMA"."ISEQ$$_xxxxx".nextval`，
> 用 `like '%NEXTVAL%'` 能命中。达梦虽然兼容 Oracle 语法，
> 但**元数据实现不同**——它把自增标记放在了内部数据字典里。

### 解法一：查 `SYSCOLUMNS.INFO2`（纯 SQL）

达梦把 IDENTITY 标记存在 `SYSCOLUMNS.INFO2` 字段，`1` 表示自增：

```sql
select sc.NAME, sc.COLID, sc.TYPE$, sc.INFO1, sc.INFO2
from SYSCOLUMNS sc
where sc.ID = (select ID from SYSOBJECTS where NAME = 'DM_PROBE_TEST');
```

实测输出：

```
NAME | COLID | TYPE$   | INFO1 | INFO2
-----|-------|---------|-------|------
ID   | 0     | BIGINT  | 0     | 1        ← 自增列，INFO2 = 1
NAME | 1     | VARCHAR | 0     | 0
```

**两个容易踩的细节**：

1. `SYSCOLUMNS` 要通过 **`SYSOBJECTS.ID`** 关联，**不能**直接 `where NAME = '表名'`
   （`SYSCOLUMNS.NAME` 是**列名**，不是表名）。我第一版写成
   `where sc.ID = (select ID from SYSOBJECTS where NAME = ... AND SUBTYPE$='STAB')`
   关联到了错误的对象，返回 0 行，白折腾一轮。
2. `SYSCOLUMNS.COLID` **从 0 开始**，而 `USER_TAB_COLUMNS.COLUMN_ID` **从 1 开始**，
   两者不能混用排序。

在若依的 XML 里这样写：

```sql
nvl((select sc.INFO2
       from SYSCOLUMNS sc
      where sc.ID = (select so.ID from SYSOBJECTS so
                      where so.NAME = c.TABLE_NAME and rownum <= 1)
        and sc.NAME = c.COLUMN_NAME), 0) as is_increment
```

### 解法二：用 JDBC 标准接口（推荐，不依赖私有字典）

如果生产环境禁止应用账号访问 `SYS` 模式对象，上面的方案会被权限挡住。
更好的办法是用 **JDBC 标准元数据接口**：

```java
try (ResultSet rs = conn.getMetaData().getColumns(null, "SYSDBA", "DM_PROBE_TEST", "%")) {
    while (rs.next()) {
        System.out.println(rs.getString("COLUMN_NAME")
                + " AUTOINC=" + rs.getString("IS_AUTOINCREMENT"));
    }
}
```

实测输出（对照组使用了非自增表）：

```
DM_ID3.ID    | BIGINT  | AUTOINC=YES      ← IDENTITY(1,1)，正确识别
DM_ID3.NAME  | VARCHAR | AUTOINC=NO
DM_NOID3.ID  | BIGINT  | AUTOINC=NO       ← 普通主键，正确区分
DM_NOID3.NAME| VARCHAR | AUTOINC=NO
```

**达梦 JDBC 驱动自己解析好了自增语义。** 这条路不碰任何达梦私有系统表，
换人大金仓、高斯时也不用改代码。

> 代价：要走 JDBC 接口就得写 Java 代码，不能只改 XML。
> 我给项目里两种方案都做了实现，按部署环境的权限情况选。

---

## 坑 2：列注释全丢了

社区流传的改法里有这么一行：

```sql
c.COLUMN_NAME as column_comment
```

看起来无害，实际是把**列名当成注释**返回。生成出来的实体类注释变成：

```java
/** REAL_NAME */
private String realName;      // 想要的是 /** 真实姓名 */
```

前端表单的 label 全成了英文列名。

### 原因

达梦的列注释不在 `USER_TAB_COLUMNS` 里，而在 **`USER_COL_COMMENTS`** 视图。

```sql
select OWNER, TABLE_NAME, COLUMN_NAME, COMMENTS
from USER_COL_COMMENTS
where TABLE_NAME = 'DM_PROBE_TEST';
```

实测：

```
OWNER  | TABLE_NAME    | COLUMN_NAME | COMMENTS
-------|---------------|-------------|---------
SYSDBA | DM_PROBE_TEST | ID          | 主键ID
SYSDBA | DM_PROBE_TEST | NAME        | 姓名
SYSDBA | DM_PROBE_TEST | AGE         | NULL
SYSDBA | DM_PROBE_TEST | AMOUNT      | 金额
```

### 解法

`left join` 上去，并用 `nvl` 兜底（无注释时回退列名，与若依原行为一致）：

```sql
nvl(cc.COMMENTS, c.COLUMN_NAME) as column_comment
...
left join USER_COL_COMMENTS cc
       on cc.TABLE_NAME = c.TABLE_NAME
      and cc.COLUMN_NAME = c.COLUMN_NAME
```

表注释同理，要查 `USER_TAB_COMMENTS`：

```sql
nvl((select tc.COMMENTS from USER_TAB_COMMENTS tc
      where tc.TABLE_NAME = obj.OBJECT_NAME), obj.OBJECT_NAME) as table_comment
```

---

## 坑 3：列类型丢长度精度，导致 javaType / htmlType 全错

社区改法里这一行：

```sql
c.DATA_TYPE as column_type
```

达梦的 `DATA_TYPE` **只返回类型名**，不带长度：

```
COLUMN_NAME | DATA_TYPE | DATA_LENGTH | DATA_PRECISION | DATA_SCALE
------------|-----------|-------------|----------------|-----------
ID          | BIGINT    | 8           | NULL           | 0
NAME        | VARCHAR   | 64          | NULL           | 0
AMOUNT      | DECIMAL   | 22          | 10             | 2
```

返回给若依的是 `VARCHAR` 而不是 `VARCHAR(64)`。

### 后果

若依 `GenUtils` 里是这样解析的：

```java
// GenUtils.java:50
column.setColumnLength(getColumnLength(column.getColumnType()));

// GenUtils.java:245-256
public static Integer getColumnLength(String columnType) {
    if (StringUtils.indexOf(columnType, "(") > 0) {
        String length = StringUtils.substringBetween(columnType, "(", ")");
        return Integer.valueOf(length);
    }
    return 0;     // ← VARCHAR 没有括号，长度算成 0
}
```

**长度恒为 0**，接着影响第 47-79 行的 `htmlType` 推断：

```java
if (StringUtils.substringBetween(column.getColumnType(), "(", ")") != null
        && Integer.parseInt(...) > 500) {
    column.setHtmlType("textarea");     // ← 长文本永远判不成 textarea
}
```

结果：`VARCHAR(1000)` 的备注字段被生成成单行 `input` 而不是 `textarea`，
`DECIMAL(10,2)` 的小数位也丢了。

### 解法：按类型拼装完整类型

```sql
(case
     when c.DATA_TYPE in ('CHAR','VARCHAR','VARCHAR2','NCHAR','NVARCHAR','NVARCHAR2','BINARY','VARBINARY')
          then c.DATA_TYPE || '(' || c.DATA_LENGTH || ')'
     when c.DATA_TYPE in ('NUMBER','DECIMAL','NUMERIC','DEC','FLOAT','DOUBLE')
          and c.DATA_PRECISION is not null
          then c.DATA_TYPE || '(' || c.DATA_PRECISION || ',' || nvl(c.DATA_SCALE, 0) || ')'
     else c.DATA_TYPE
 end) as column_type
```

实测拼装结果：

```
NAME         → VARCHAR(64)
AMOUNT       → DECIMAL(10,2)
BIGINT / INT / DATETIME → 原样，不加括号
```

⚠️ 注意 **`BIGINT` 不要拼长度**。`DATA_LENGTH = 8`，如果无脑拼成 `BIGINT(8)`，
`GenUtils.getDbType()` 会截出 `BIGINT` 没问题，但某些模板里会当成有精度的数值处理。
数值类型只在 `DATA_PRECISION is not null` 时才拼。

---

## 坑 4：主键识别基本可用，但 join 条件有隐患

主键这块社区改法是对的，可以直接保留：

```sql
left join (select ucc.TABLE_NAME, ucc.COLUMN_NAME
             from USER_CONSTRAINTS uc
             join USER_CONS_COLUMNS ucc
               on ucc.CONSTRAINT_NAME = uc.CONSTRAINT_NAME
            where uc.CONSTRAINT_TYPE = 'P') pk
       on pk.TABLE_NAME = c.TABLE_NAME and pk.COLUMN_NAME = c.COLUMN_NAME
```

实测正确返回：

```
CONSTRAINT_NAME | CONSTRAINT_TYPE | COLUMN_NAME | POSITION
----------------|-----------------|-------------|---------
PK_DM_PROBE     | P               | ID          | 1
```

**但注意**：上面的 join 只按 `CONSTRAINT_NAME` 关联，没带 `OWNER` 或 `TABLE_NAME`。
达梦的约束名在同一 schema 内唯一，多数情况没问题；
但如果库里有多个 schema 且存在同名约束，会串数据。

**建议改成**：

```sql
join USER_CONS_COLUMNS ucc
  on ucc.CONSTRAINT_NAME = uc.CONSTRAINT_NAME
 and ucc.TABLE_NAME      = uc.TABLE_NAME      -- 补上这一行
```

---

## 坑 5：`concat()` 其实不用改，但 `date_format` / `sysdate()` 要小心

网上很多文章说「达梦不支持 `concat()`，要改成 `||`」。实测**两种都支持**：

```sql
select concat('a','b') as c1, 'a' || 'b' as c2 from dual;
```

```
C1 | C2
---|--
ab | ab
```

所以这一步不是必须的。但下面两处**真的**要改：

**① `date_format()` 不可用**

若依 `GenTableMapper.selectGenTableList` 里有：

```sql
date_format(create_time,'%Y%m%d') >= date_format(#{params.beginTime},'%Y%m%d')
```

达梦没有 `date_format`，要换成 `to_char`：

```sql
to_char(create_time, 'YYYYMMDD') >= to_char(#{params.beginTime}, 'YYYYMMDD')
```

**② `sysdate()` 要写成 `sysdate`**

若依的 insert/update 语句里写的是 `sysdate()`（MySQL 风格带括号），
达梦要用 `sysdate`（不带括号）。

**③ 分页方言**

`application.yml` 里加：

```yaml
pagehelper:
  helperDialect: dm
```

---

## 完整补丁

把上面的修复拼起来，替换若依 `GenTableColumnMapper.xml` 的
`selectDbTableColumnsByName`：

```sql
select c.COLUMN_NAME as column_name,
       (case when c.NULLABLE = 'N' then '1' else '0' end) as is_required,
       (case when pk.COLUMN_NAME is not null then '1' else '0' end) as is_pk,
       c.COLUMN_ID as sort,
       nvl(cc.COMMENTS, c.COLUMN_NAME) as column_comment,
       nvl((select sc.INFO2
              from SYSCOLUMNS sc
             where sc.ID = (select so.ID from SYSOBJECTS so
                             where so.NAME = c.TABLE_NAME and rownum <= 1)
               and sc.NAME = c.COLUMN_NAME), 0) as is_increment,
       (case
            when c.DATA_TYPE in ('CHAR','VARCHAR','VARCHAR2','NCHAR','NVARCHAR','NVARCHAR2','BINARY','VARBINARY')
                 then c.DATA_TYPE || '(' || c.DATA_LENGTH || ')'
            when c.DATA_TYPE in ('NUMBER','DECIMAL','NUMERIC','DEC','FLOAT','DOUBLE')
                 and c.DATA_PRECISION is not null
                 then c.DATA_TYPE || '(' || c.DATA_PRECISION || ',' || nvl(c.DATA_SCALE, 0) || ')'
            else c.DATA_TYPE
        end) as column_type
  from USER_TAB_COLUMNS c
  left join USER_COL_COMMENTS cc
         on cc.TABLE_NAME = c.TABLE_NAME and cc.COLUMN_NAME = c.COLUMN_NAME
  left join (select ucc.TABLE_NAME, ucc.COLUMN_NAME
               from USER_CONSTRAINTS uc
               join USER_CONS_COLUMNS ucc
                 on ucc.CONSTRAINT_NAME = uc.CONSTRAINT_NAME
                and ucc.TABLE_NAME = uc.TABLE_NAME
              where uc.CONSTRAINT_TYPE = 'P') pk
         on pk.TABLE_NAME = c.TABLE_NAME and pk.COLUMN_NAME = c.COLUMN_NAME
 where c.TABLE_NAME = #{tableName}
 order by c.COLUMN_ID
```

**自检方式**：替换完直接在达梦客户端跑一遍，看 IDENTITY 列是不是返回 `1`。
我写了个自动化验证脚本，12 项断言全过才算数——完整代码见仓库。

---

## 验证结果

修改后对真实达梦跑集成测试：

```
JUnit version 4.13.2
[达梦集成测试] 已连接 jdbc:dm://localhost:5236 产品=DM DBMS 版本=8.0.0.0
.......
Time: 0.582
OK (7 tests)
```

补丁 SQL 的字段级验证（建表 → 打补丁 → 逐列断言）：

```
列名        | 必填 | 主键 | 注释     | 自增 | 完整类型
------------|------|------|----------|------|-------------
ID          | 1    | 1    | 主键ID   | 1    | BIGINT
REAL_NAME   | 1    | 0    | 真实姓名 | 0    | VARCHAR(64)
AMOUNT      | 0    | 0    | 金额     | 0    | DECIMAL(10,2)
REMARK      | 0    | 0    | REMARK   | 0    | VARCHAR(500)

>>> 全部断言通过 <<<
```

---

## 小结

| 坑 | 现象 | 根因 | 修复 |
|----|------|------|------|
| 1 | 自增主键被当普通字段，插入报错 | 达梦 IDENTITY 列的 `DATA_DEFAULT` 为 `NULL`，`like '%NEXTVAL%'` 恒 false | 查 `SYSCOLUMNS.INFO2`，或用 JDBC `IS_AUTOINCREMENT` |
| 2 | 字段注释变成列名 | 列注释在 `USER_COL_COMMENTS`，不在 `USER_TAB_COLUMNS` | `left join USER_COL_COMMENTS` |
| 3 | `VARCHAR` 无长度，`javaType`/`htmlType` 全错 | `DATA_TYPE` 不带长度，若依靠括号解析 | 按类型拼 `(长度)` / `(精度,小数位)` |
| 4 | 多 schema 下主键可能串数据 | join 只按 `CONSTRAINT_NAME` | 补 `and ucc.TABLE_NAME = uc.TABLE_NAME` |
| 5 | `date_format` 报错 | 达梦无此函数 | 改 `to_char` |

**最需要警惕的是坑 1**：它不报错、不抛异常，生成的代码能编译能启动，
只在真正插入数据时才暴露。如果你的项目已经按社区方案改完且「看起来正常」了，
建议立刻建一张带 IDENTITY 主键的表，用代码生成器生成一次，检查
`is_increment` 是不是 `1`。

---

*本文的结论全部基于真实达梦实例的可复现实验，排查脚本保留在仓库 `probe/` 目录。*
