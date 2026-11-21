package com.hjq.CodeLess.ai.tools;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Discovers tool beans and creates them for one Vue generation workspace. */
@Component
public class ToolManager {
    private final ListableBeanFactory beanFactory;

    public ToolManager(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public BaseTool getTool(String toolName, VueProjectWorkspace workspace) {
        if (!beanFactory.containsBean(toolName) || !beanFactory.isTypeMatch(toolName, BaseTool.class)) {
            return null;
        }
        return (BaseTool) beanFactory.getBean(toolName, workspace);
    }

    public BaseTool[] getAllTools(VueProjectWorkspace workspace) {
        return Arrays.stream(beanFactory.getBeanNamesForType(BaseTool.class, true, false))
                .map(name -> (BaseTool) beanFactory.getBean(name, workspace))
                .toArray(BaseTool[]::new);
    }
}
