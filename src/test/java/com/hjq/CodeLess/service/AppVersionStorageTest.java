package com.hjq.CodeLess.service;

import com.hjq.CodeLess.ai.model.MultiFileCodeResult;
import com.hjq.CodeLess.ai.tools.FileWriteTool;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppVersionStorageTest {
    @TempDir Path temp;

    @Test
    void snapshotsAreImmutableAndRollbackRestoresAllFiles() throws Exception {
        var storage = new AppVersionStorage(temp.resolve("output"), temp.resolve("versions"));
        var first = code("old", "old css", "old js");
        Path v1 = storage.saveGenerated(1L, CodeGenTypeEnum.MULTI_FILE, 1, first);
        try (var activation = storage.activate(1L, "multi_file", v1)) { activation.commit(); }
        Path v2 = storage.saveGenerated(1L, CodeGenTypeEnum.MULTI_FILE, 2, code("new", "", ""));
        try (var activation = storage.activate(1L, "multi_file", v2)) { activation.commit(); }
        assertFalse(Files.exists(temp.resolve("output/multi_file_1/style.css")));
        assertEquals("old css", storage.readFiles(1L, "multi_file", 1).get("style.css"));
        try (var activation = storage.activate(1L, "multi_file", v1)) { activation.commit(); }
        assertEquals("old", Files.readString(temp.resolve("output/multi_file_1/index.html")));
        assertEquals("old js", Files.readString(temp.resolve("output/multi_file_1/script.js")));
        assertThrows(RuntimeException.class, () -> storage.saveGenerated(1L, CodeGenTypeEnum.MULTI_FILE, 1, first));
    }

    @Test
    void snapshotsVueProjectSourcesAndKeepsWritesInTheWorkspace() {
        var storage = new AppVersionStorage(temp.resolve("output"), temp.resolve("versions"));
        Path workspace = storage.createVueProjectWorkspace();
        var tool = new FileWriteTool(workspace);
        tool.writeFile("../outside.txt", "outside");
        tool.writeFile(temp.resolve("outside.txt").toString(), "outside");
        assertFalse(Files.exists(workspace.getParent().resolve("outside.txt")));
        assertFalse(Files.exists(temp.resolve("outside.txt")));
        tool.writeFile("index.html", "<div id=\"app\"></div>");
        tool.writeFile("package.json", "{}");
        tool.writeFile("src/App.vue", "<template>你好</template>");
        tool.stopWriting();
        tool.writeFile("src/App.vue", "late write");

        storage.saveGenerated(1L, CodeGenTypeEnum.VUE_PROJECT, 1, workspace);
        storage.discard(workspace);

        assertEquals("<template>你好</template>", storage.readFiles(1L, "vue_project", 1).get("src/App.vue"));
    }

    @Test
    void failedMetadataCommitRestoresPreviousPreview() throws Exception {
        var storage = new AppVersionStorage(temp.resolve("output"), temp.resolve("versions"));
        Path v1 = storage.saveGenerated(1L, CodeGenTypeEnum.MULTI_FILE, 1, code("old", "", ""));
        try (var activation = storage.activate(1L, "multi_file", v1)) { activation.commit(); }
        Path v2 = storage.saveGenerated(1L, CodeGenTypeEnum.MULTI_FILE, 2, code("new", "", ""));
        try (var ignored = storage.activate(1L, "multi_file", v2)) { /* Simulate database failure. */ }
        assertEquals("old", Files.readString(temp.resolve("output/multi_file_1/index.html")));
    }

    @Test
    void validatesPathsAndMissingVersions() {
        var storage = new AppVersionStorage(temp.resolve("output"), temp.resolve("versions"));
        assertThrows(RuntimeException.class, () -> storage.readFiles(1L, "../outside", 1));
        assertThrows(RuntimeException.class, () -> storage.readFiles(1L, "multi_file", 0));
        assertThrows(RuntimeException.class, () -> storage.readFiles(1L, "multi_file", 12));
    }

    private MultiFileCodeResult code(String html, String css, String js) {
        var result = new MultiFileCodeResult();
        result.setHtmlCode(html);
        result.setCssCode(css);
        result.setJsCode(js);
        return result;
    }
}
