-- 可重复执行；历史应用保留公开状态，新建应用默认私有。
use CodeLess;
set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='description')=0, 'alter table app add column `description` varchar(500) null comment ''应用公开描述''', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='tags')=0, 'alter table app add column `tags` text null comment ''JSON 标签数组''', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='visibility')=0, 'alter table app add column `visibility` varchar(20) not null default ''public'' comment ''public/private''', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='featureStatus')=0, 'alter table app add column `featureStatus` varchar(20) not null default ''none'' comment ''none/pending/approved/rejected''', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='featureRequestMessage')=0, 'alter table app add column `featureRequestMessage` varchar(500) null', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='featureReviewMessage')=0, 'alter table app add column `featureReviewMessage` varchar(500) null', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='featureRequestedTime')=0, 'alter table app add column `featureRequestedTime` datetime null', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

set @migration_sql = if((select count(*) from information_schema.columns where table_schema=database() and table_name='app' and column_name='featureReviewedTime')=0, 'alter table app add column `featureReviewedTime` datetime null', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;

alter table app alter column visibility set default 'private';
update app set tags='[]' where tags is null;
update app set featureStatus='approved' where priority=99 and featureStatus='none' and visibility='public';
set @migration_sql=if((select count(*) from information_schema.statistics where table_schema=database() and table_name='app' and index_name='idx_app_discovery')=0, 'create index idx_app_discovery on app(visibility,isDelete,priority,createTime)', 'select 1');
prepare app_discovery_migration from @migration_sql;
execute app_discovery_migration;
deallocate prepare app_discovery_migration;
