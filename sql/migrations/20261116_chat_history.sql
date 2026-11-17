-- 对话历史表；可重复执行，不影响已有数据。
use CodeLess;

create table if not exists chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),
    INDEX idx_createTime (createTime),
    INDEX idx_appId_createTime (appId, createTime)
) comment '对话历史' collate = utf8mb4_unicode_ci;
