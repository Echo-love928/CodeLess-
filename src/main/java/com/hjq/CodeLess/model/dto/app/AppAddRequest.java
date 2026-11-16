package com.hjq.CodeLess.model.dto.app;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppAddRequest implements Serializable {

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    private String visibility;
    private String description;
    private java.util.List<String> tags;

    private static final long serialVersionUID = 1L;
}

