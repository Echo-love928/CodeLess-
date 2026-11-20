package com.hjq.CodeLess.core;

import com.hjq.CodeLess.core.builder.VueProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VueProjectBuilderTest {

    @Test
    void asynchronousBuildRunsOnAVirtualThread() throws Exception {
        var virtualThread = new CompletableFuture<Boolean>();
        var builder = new VueProjectBuilder() {
            @Override
            public boolean buildProject(String projectPath) {
                virtualThread.complete(Thread.currentThread().isVirtual());
                return true;
            }
        };

        builder.buildProjectAsync("unused");

        assertTrue(virtualThread.get(5, TimeUnit.SECONDS));
    }
}
