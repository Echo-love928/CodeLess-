package com.hjq.CodeLess.model.dto.app;

import java.io.Serializable;
import lombok.Data;

/**
 * 管理员更新应用请求
 */
@Data
public class AppAdminUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    private String visibility;
    private String description;
    private java.util.List<String> tags;

    /**
     * 优先级
     */
    private Integer priority;

    private static final long serialVersionUID = 1L;
}
