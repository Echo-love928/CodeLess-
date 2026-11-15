-- 在更新的后端启动前执行，可重复运行；保留所有现有应用和版本。
use CodeLess;
set @migration_sql = if((select count(*) from information_schema.columns
    where table_schema=database() and table_name='app' and column_name='version') = 0,
    'alter table app add column version int not null default 0 comment ''当前代码版本''', 'select 1');
prepare app_version_migration from @migration_sql;
execute app_version_migration;
deallocate prepare app_version_migration;

set @migration_sql = if((select count(*) from information_schema.columns
    where table_schema=database() and table_name='app' and column_name='deployedVersion') = 0,
    'alter table app add column deployedVersion int null comment ''线上代码版本''', 'select 1');
prepare app_version_migration from @migration_sql;
execute app_version_migration;
deallocate prepare app_version_migration;

create table if not exists app_version
(
    appId bigint not null comment '应用 ID',
    version int not null comment '版本号',
    parentVersion int null comment '基于哪个版本生成',
    prompt text null comment '本次生成需求',
    source varchar(20) not null comment 'generated/imported',
    createTime datetime not null default CURRENT_TIMESTAMP,
    primary key (appId, version)
) comment '应用代码版本' collate = utf8mb4_unicode_ci;
