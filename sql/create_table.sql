# 数据库初始化

-- 创建库
create database if not exists CodeLess;

-- 切换库
use CodeLess;

-- 用户表
-- 以下是建表语句

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
    ) comment '用户' collate = utf8mb4_unicode_ci;

-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    `description` varchar(500) null comment '应用公开描述',
    `tags` text null comment 'JSON 标签数组',
    `visibility` varchar(20) not null default 'private' comment 'public/private',
    `featureStatus` varchar(20) not null default 'none' comment 'none/pending/approved/rejected',
    `featureRequestMessage` varchar(500) null,
    `featureReviewMessage` varchar(500) null,
    `featureRequestedTime` datetime null,
    `featureReviewedTime` datetime null,
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    generationStatus varchar(20) default 'pending' not null comment 'pending/generating/completed/stopped/failed/unknown',
    deploymentStatus varchar(20) default 'offline' not null comment 'online/offline',
    version int not null default 0 comment '当前代码版本，0 表示尚无快照',
    deployedVersion int null comment '线上代码版本',
    downloadCount bigint not null default 0 comment '成功下载代码次数',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId),           -- 提升基于用户 ID 的查询性能
    INDEX idx_app_discovery (visibility,isDelete,priority,createTime)
) comment '应用' collate = utf8mb4_unicode_ci;

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

-- 对话历史表
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;
