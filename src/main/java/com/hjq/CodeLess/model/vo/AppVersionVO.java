package com.hjq.CodeLess.model.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppVersionVO {
    private Long appId;
    private Integer version;
    private Integer parentVersion;
    private String prompt;
    private String source;
    private LocalDateTime createTime;
}
