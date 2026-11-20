package com.hjq.CodeLess.core.handler;

import cn.hutool.json.JSONUtil;
import com.hjq.CodeLess.ai.model.message.StreamMessageTypeEnum;
import com.hjq.CodeLess.constant.AppConstant;
import com.hjq.CodeLess.core.builder.VueProjectBuilder;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.hjq.CodeLess.service.AppGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.file.Path;

/** Vue 工程模式从结构化消息中提取 AI 正文。 */
@Component
@RequiredArgsConstructor
public class JsonMessageStreamHandler {

    private final VueProjectBuilder vueProjectBuilder;
    private final AppGenerationService appGenerationService;

    Flux<String> buildOnComplete(Flux<String> originFlux, long appId) {
        return originFlux.doOnComplete(() -> {
            String projectPath = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId).toString();
            appGenerationService.startVueBuild(appId, () -> vueProjectBuilder.buildProjectAsync(projectPath));
        });
    }

    String extractAiText(String chunk) {
        var message = JSONUtil.parseObj(chunk);
        if (StreamMessageTypeEnum.AI_RESPONSE.getValue().equals(message.getStr("type"))) {
            return message.getStr("data", "");
        }
        return "";
    }
}
