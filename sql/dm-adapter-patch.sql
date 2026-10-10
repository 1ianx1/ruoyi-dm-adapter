-- =====================================================================
--  ruoyi-dm-adapter · 达梦 DM8 适配 SQL 补丁
--  适用版本：RuoYi-Vue3 3.8.x / 3.9.x（ruoyi-generator 模块）
--  数据库：达梦 DM8（实测 8.0.0.0 + DmJdbcDriver18 8.1.3.140）
-- =====================================================================
--
--  使用方式（推荐）：
--  不要直接改若依源码，而是把下面两个 SQL 片段替换进
--    ruoyi-generator/src/main/resources/mapper/generator/GenTableColumnMapper.xml
--  的 <select id="selectDbTableColumnsByName"> 语句中。
--  完整替换后的 XML 见本仓库 docs/GenTableColumnMapper.xml。
--
--  ─────────────────────────────────────────────────────────────────
--  若依原版（MySQL）写法：
--
--  select column_name, (case when (is_nullable = 'no' && column_key != 'PRI')
--    then '1' else '0' end) as is_required, (case when column_key = 'PRI'
--    then '1' else '0' end) as is_pk, ordinal_position as sort,
--    column_comment, (case when extra = 'auto_increment' then '1' else '0' end)
--    as is_increment, column_type
--  from information_schema.columns
--  where table_schema = (select database()) and table_name = (#{tableName})
--  order by ordinal_position
--
--  社区流传的「达梦改法」：
--
--  select c.COLUMN_NAME as column_name,
--    (case when c.NULLABLE = 'N' then '1' else '0' end) as is_required,
--    (case when pk.COLUMN_NAME is not null then '1' else '0' end) as is_pk,
--    c.COLUMN_ID as sort,
--    c.COLUMN_NAME as column_comment,          -- ← BUG 1：拿列名当注释
--    (case when c.DATA_DEFAULT is not null
--          and c.DATA_DEFAULT like '%NEXTVAL%'
--          then '1' else '0' end) as is_increment,  -- ← BUG 2：达梦上恒为 0
--    c.DATA_TYPE as column_type                -- ← BUG 3：丢失长度与精度
--  from USER_TAB_COLUMNS c ...
--
--  本补丁修复以上三处，并保持在达梦上的实测可用性。
-- =====================================================================


-- =====================================================================
--  【补丁 1】GenTableColumnMapper.xml → selectDbTableColumnsByName
--  修复：列注释 + 自增列 + 完整列类型
-- =====================================================================

select c.COLUMN_NAME                                                                  as column_name,
       (case when c.NULLABLE = 'N' then '1' else '0' end)                             as is_required,
       (case when pk.COLUMN_NAME is not null then '1' else '0' end)                   as is_pk,
       c.COLUMN_ID                                                                    as sort,
       -- 修复 BUG 1：列注释取自 USER_COL_COMMENTS，无注释时回退列名
       nvl(cc.COMMENTS, c.COLUMN_NAME)                                                as column_comment,
       -- 修复 BUG 2：达梦 IDENTITY 列判定
       --   达梦 IDENTITY 列的 USER_TAB_COLUMNS.DATA_DEFAULT 为 NULL，
       --   无法沿用 'NEXTVAL' 判定；真实标记位于内部数据字典 SYSCOLUMNS.INFO2
       --   （INFO2 = 1 表示自增列）。已用真实达梦实例验证。
       nvl((select sc.INFO2
              from SYSCOLUMNS sc
             where sc.ID = (select so.ID from SYSOBJECTS so
                             where so.NAME = c.TABLE_NAME and rownum <= 1)
               and sc.NAME = c.COLUMN_NAME), 0)                                       as is_increment,
       -- 修复 BUG 3：拼装带长度/精度的完整类型，供 GenUtils 解析
       --   字符类型 → TYPE(长度)，数值类型 → TYPE(精度,小数位)，其余 → TYPE
       (case
            when c.DATA_TYPE in ('CHAR','VARCHAR','VARCHAR2','NCHAR','NVARCHAR','NVARCHAR2','BINARY','VARBINARY')
                 then c.DATA_TYPE || '(' || c.DATA_LENGTH || ')'
            when c.DATA_TYPE in ('NUMBER','DECIMAL','NUMERIC','DEC','FLOAT','DOUBLE')
                 and c.DATA_PRECISION is not null
                 then c.DATA_TYPE || '(' || c.DATA_PRECISION || ',' || nvl(c.DATA_SCALE, 0) || ')'
            else c.DATA_TYPE
        end)                                                                          as column_type
  from USER_TAB_COLUMNS c
  left join USER_COL_COMMENTS cc
         on cc.TABLE_NAME = c.TABLE_NAME and cc.COLUMN_NAME = c.COLUMN_NAME
  left join (select ucc.TABLE_NAME, ucc.COLUMN_NAME
               from USER_CONSTRAINTS uc
               join USER_CONS_COLUMNS ucc
                 on ucc.CONSTRAINT_NAME = uc.CONSTRAINT_NAME
              where uc.CONSTRAINT_TYPE = 'P') pk
         on pk.TABLE_NAME = c.TABLE_NAME and pk.COLUMN_NAME = c.COLUMN_NAME
 where c.TABLE_NAME = #{tableName}
 order by c.COLUMN_ID;


-- =====================================================================
--  【补丁 2】MyBatis 多数据库方言配置
--  文件：ruoyi-framework/src/main/java/com/ruoyi/framework/config/MyBatisConfig.java
--  目的：让同一套 XML 能同时服务 MySQL 与达梦（<choose> 按 _databaseId 分支）
-- =====================================================================

-- Java 配置片段（供参考，非 SQL）：
--
--   @Bean
--   public DatabaseIdProvider databaseIdProvider() {
--       VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
--       Properties p = new Properties();
--       p.setProperty("MySQL", "mysql");
--       p.setProperty("DM DBMS", "dm");        // ← 达梦，实测 getDatabaseProductName() 返回 "DM DBMS"
//       p.setProperty("PostgreSQL", "pgsql");
--       provider.setProperties(p);
--       return provider;
--   }
--
-- 随后在 Mapper XML 中用：
--   <choose>
--     <when test="'dm' == _databaseId">  ...达梦 SQL...  </when>
--     <otherwise>                        ...MySQL SQL... </otherwise>
--   </choose>


-- =====================================================================
--  【补丁 3】PageHelper 分页方言
--  文件：ruoyi-admin/src/main/resources/application.yml
-- =====================================================================
-- pagehelper:
--   helperDialect: dm        # 达梦方言（实测有效，无需额外配置）


-- =====================================================================
--  【补丁 4】GenTableMapper.xml → selectDbTableList 系列（表清单 + 表注释）
--  若依原版查 information_schema.tables，达梦需改查 USER_OBJECTS / USER_TAB_COMMENTS
-- =====================================================================

select obj.OBJECT_NAME                                                       as table_name,
       nvl((select tc.COMMENTS from USER_TAB_COMMENTS tc
             where tc.TABLE_NAME = obj.OBJECT_NAME), obj.OBJECT_NAME)        as table_comment,
       obj.CREATED                                                           as create_time,
       obj.LAST_DDL_TIME                                                     as update_time
  from USER_OBJECTS obj
 where obj.OBJECT_TYPE = 'TABLE'
   and lower(obj.OBJECT_NAME) like lower('%' || #{tableName} || '%')
 order by obj.CREATED desc;


-- =====================================================================
--  【自检脚本】替换完 XML 后，可直接在达梦客户端跑这段验证
--  预期：IDENTITY 列返回 1，普通列返回 0，注释正确显示
-- =====================================================================

-- 把 T_YOUR_TABLE 换成你自己的表名
select c.COLUMN_NAME                                                   as 列名,
       nvl(cc.COMMENTS, c.COLUMN_NAME)                                 as 注释,
       (case
            when c.DATA_TYPE in ('CHAR','VARCHAR','VARCHAR2','NCHAR','NVARCHAR','NVARCHAR2')
                 then c.DATA_TYPE || '(' || c.DATA_LENGTH || ')'
            when c.DATA_TYPE in ('NUMBER','DECIMAL','NUMERIC','DEC','FLOAT','DOUBLE')
                 and c.DATA_PRECISION is not null
                 then c.DATA_TYPE || '(' || c.DATA_PRECISION || ',' || nvl(c.DATA_SCALE,0) || ')'
            else c.DATA_TYPE
        end)                                                           as 完整类型,
       nvl((select sc.INFO2 from SYSCOLUMNS sc
             where sc.ID = (select so.ID from SYSOBJECTS so
                             where so.NAME = c.TABLE_NAME and rownum <= 1)
               and sc.NAME = c.COLUMN_NAME), 0)                        as 是否自增
  from USER_TAB_COLUMNS c
  left join USER_COL_COMMENTS cc
         on cc.TABLE_NAME = c.TABLE_NAME and cc.COLUMN_NAME = c.COLUMN_NAME
 where c.TABLE_NAME = 'T_YOUR_TABLE'
 order by c.COLUMN_ID;
