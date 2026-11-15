package com.hjq.CodeLess.model.vo;

import lombok.Data;
import java.util.Map;

@Data
public class AppVersionDetailVO {
    private Integer version;
    private Map<String, String> files;
}
