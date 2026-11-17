package com.hjq.CodeLess.service;

import com.mybatisflex.core.paginate.Page;
import com.hjq.CodeLess.model.dto.chathistory.ChatHistoryQueryRequest;
import com.hjq.CodeLess.model.entity.ChatHistory;
import com.hjq.CodeLess.model.entity.User;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;

import java.time.LocalDateTime;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/Echo-love928?tab=repositories">CodeLess</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加一条对话消息。
     *
     * @param appId 应用 ID
     * @param message 消息内容
     * @param messageType 消息类型（user/ai）
     * @param userId 用户 ID
     * @return 是否保存成功
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 删除指定应用的对话历史。
     *
     * @param appId 应用 ID
     * @return 是否删除成功
     */
    boolean deleteByAppId(Long appId);

    /**
     * 分页查询某 APP 的对话记录
     * @param appId
     * @param pageSize
     * @param lastCreateTime
     * @param loginUser
     * @return
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize,
                                               LocalDateTime lastCreateTime,
                                               User loginUser);

    /**
     * 构造查询条件
     * @param chatHistoryQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);
}
