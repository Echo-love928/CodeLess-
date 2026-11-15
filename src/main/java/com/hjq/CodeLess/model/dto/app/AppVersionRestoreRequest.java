package com.hjq.CodeLess.model.dto.app;

import lombok.Data;

@Data
public class AppVersionRestoreRequest {
    private Long appId;
    private Integer version;
}
