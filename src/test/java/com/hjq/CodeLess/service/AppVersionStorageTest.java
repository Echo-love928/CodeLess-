package com.hjq.CodeLess.service;

import com.hjq.CodeLess.ai.model.MultiFileCodeResult;
import com.hjq.CodeLess.ai.tools.FileDeleteTool;
import com.hjq.CodeLess.ai.tools.FileDirReadTool;
import com.hjq.CodeLess.ai.tools.FileModifyTool;
import com.hjq.CodeLess.ai.tools.FileReadTool;
import com.hjq.CodeLess.ai.tools.FileWriteTool;
import com.hjq.CodeLess.ai.tools.VueProjectWorkspace;
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
    void vueToolsEditACopyOfTheCurrentVersion() throws Exception {
        var storage = new AppVersionStorage(temp.resolve("output"), temp.resolve("versions"));
        var type = CodeGenTypeEnum.VUE_PROJECT;
        var original = Files.createDirectories(temp.resolve("output/vue_project_1"));
        var originalWriter = new FileWriteTool(original);
        originalWriter.writeFile("index.html", "<div id=\"app\"></div>");
        originalWriter.writeFile("package.json", "{}");
        originalWriter.writeFile("src/App.vue", "<footer>2025</footer>");
        originalWriter.writeFile("src/Keep.vue", "保留内容");
        originalWriter.writeFile("src/Old.vue", "待删除");
        originalWriter.writeFile("dist/compiled.js", "stale build");
        originalWriter.writeFile("node_modules/example/package.json", "{}");
        var snapshot = storage.captureExisting(1L, type.getValue(), 1);
        assertFalse(Files.exists(snapshot.resolve("node_modules")));
        assertTrue(Files.exists(snapshot.resolve("dist/compiled.js")));

        var editPath = storage.createVueProjectWorkspace();
        storage.seedVueProjectWorkspace(1L, 1, editPath);
        var workspace = new VueProjectWorkspace(editPath);
        var read = new FileReadTool(workspace);
        var list = new FileDirReadTool(workspace);
        var modify = new FileModifyTool(workspace);
        var delete = new FileDeleteTool(workspace);
        var outside = Files.writeString(editPath.getParent().resolve("outside.txt"), "不可访问");

        assertFalse(Files.exists(editPath.resolve("dist")));
        assertEquals("项目目录结构:\nindex.html\npackage.json\nsrc/\nsrc/App.vue\nsrc/Keep.vue\nsrc/Old.vue",
                list.readDir(""));
        assertEquals("<footer>2025</footer>", read.readFile("src/App.vue"));
        assertTrue(read.readFile("../outside.txt").contains("路径必须位于项目目录内"));
        assertTrue(list.readDir(editPath.getParent().toString()).contains("路径必须位于项目目录内"));
        assertTrue(modify.modifyFile(outside.toString(), "不可访问", "改动").contains("路径必须位于项目目录内"));
        assertTrue(delete.deleteFile("../outside.txt").contains("路径必须位于项目目录内"));
        assertTrue(delete.deleteFile("package.json").contains("不允许删除"));
        assertEquals("不可访问", Files.readString(outside));

        assertEquals("文件修改成功: src/App.vue", modify.modifyFile("src/App.vue", "2025", "2026"));
        assertEquals("文件删除成功: src/Old.vue", delete.deleteFile("src/Old.vue"));
        workspace.close();
        assertTrue(modify.modifyFile("src/App.vue", "2026", "2030").contains("生成任务已结束"));
        assertTrue(delete.deleteFile("src/Keep.vue").contains("生成任务已结束"));
        storage.saveGenerated(1L, type, 2, editPath);

        var previous = storage.readFiles(1L, type.getValue(), 1);
        assertEquals("<footer>2025</footer>", previous.get("src/App.vue"));
        assertTrue(previous.containsKey("src/Old.vue"));
        var edited = storage.readFiles(1L, type.getValue(), 2);
        assertEquals("<footer>2026</footer>", edited.get("src/App.vue"));
        assertEquals("保留内容", edited.get("src/Keep.vue"));
        assertFalse(edited.containsKey("src/Old.vue"));
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
