-- 已有数据库升级；重复执行时保留已累计的下载次数。
use CodeLess;

set @migration_sql = if((
    select count(*) from information_schema.columns
    where table_schema = database() and table_name = 'app' and column_name = 'downloadCount'
) = 0,
    'alter table app add column downloadCount bigint not null default 0 comment ''成功下载代码次数''',
    'select 1');
prepare app_download_migration from @migration_sql;
execute app_download_migration;
deallocate prepare app_download_migration;
