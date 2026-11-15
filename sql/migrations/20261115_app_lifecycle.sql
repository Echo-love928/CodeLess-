-- 已有数据库升级；新数据库使用 create_table.sql。
-- 可重复执行；分别检查两个字段，兼容只完成一部分迁移的数据库。
use CodeLess;

set @missing_generation_status = (
    select count(*) = 0 from information_schema.columns
    where table_schema = database() and table_name = 'app' and column_name = 'generationStatus'
);
set @migration_sql = if(@missing_generation_status,
    'alter table app add column generationStatus varchar(20) not null default ''unknown'' comment ''pending/generating/completed/stopped/failed/unknown'' after codeGenType',
    'select 1');
prepare app_lifecycle_migration from @migration_sql;
execute app_lifecycle_migration;
deallocate prepare app_lifecycle_migration;

-- 旧数据先标记 unknown，由后端启动时核对生成文件；以后新建的应用默认 pending。
alter table app alter column generationStatus set default 'pending';

set @missing_deployment_status = (
    select count(*) = 0 from information_schema.columns
    where table_schema = database() and table_name = 'app' and column_name = 'deploymentStatus'
);
set @migration_sql = if(@missing_deployment_status,
    'alter table app add column deploymentStatus varchar(20) not null default ''offline'' comment ''online/offline'' after generationStatus',
    'select 1');
prepare app_lifecycle_migration from @migration_sql;
execute app_lifecycle_migration;
deallocate prepare app_lifecycle_migration;

-- 只初始化本次新增的字段，重复执行时保留现有的上线、下线和生成状态。
update app
set deploymentStatus = case when deployKey is not null and deployKey <> '' then 'online' else 'offline' end
where @missing_deployment_status = 1;
