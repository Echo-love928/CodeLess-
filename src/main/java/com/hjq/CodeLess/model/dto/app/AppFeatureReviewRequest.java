package com.hjq.CodeLess.model.dto.app;
import lombok.Data;
@Data
public class AppFeatureReviewRequest {
    private Long appId;
    private Boolean approved;
    private String reviewMessage;
}
