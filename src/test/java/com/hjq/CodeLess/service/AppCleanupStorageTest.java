package com.hjq.CodeLess.service;

import com.hjq.CodeLess.model.entity.App;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppCleanupStorageTest {
    @TempDir Path temp;

    private AppCleanupStorage storage() {
        return new AppCleanupStorage(temp.resolve("output"), temp.resolve("versions"), temp.resolve("deploy"), temp.resolve("cleanup"));
    }

    private void file(String name) throws IOException {
        Path path = temp.resolve(name);
        Files.createDirectories(path.getParent()); Files.writeString(path, "keep");
    }

    @Test void deletesOnlyAssociatedFilesIncludingCoversAndAllSnapshots() throws Exception {
        file("output/multi_file_1/cover.png"); file("versions/multi_file_1/v1/index.html");
        file("versions/html_1/v2/index.html"); file("deploy/abc123/index.html");
        file("output/multi_file_2/index.html"); file("deploy/other/index.html");
        var storage = storage();
        var job = storage.prepare(App.builder().id(1L).deployKey("abc123").build());
        assertFalse(Files.exists(temp.resolve("output/multi_file_1")));
        assertFalse(Files.exists(temp.resolve("versions/multi_file_1")));
        assertFalse(Files.exists(temp.resolve("deploy/abc123")));
        storage.purge(job);
        storage.release(job);
        assertTrue(storage.jobs().isEmpty());
        assertTrue(Files.exists(temp.resolve("output/multi_file_2/index.html")));
        assertTrue(Files.exists(temp.resolve("deploy/other/index.html")));
    }

    @Test void rollbackRestoresAllDirectoriesAfterDatabaseFailure() throws Exception {
        file("output/html_1/index.html"); file("versions/html_1/v1/index.html"); file("deploy/abc123/index.html");
        var storage = storage();
        var job = storage.prepare(App.builder().id(1L).deployKey("abc123").build());
        storage.restore(job); storage.release(job);
        assertEquals("keep", Files.readString(temp.resolve("output/html_1/index.html")));
        assertTrue(Files.exists(temp.resolve("versions/html_1/v1/index.html")));
        assertTrue(Files.exists(temp.resolve("deploy/abc123/index.html")));
        assertTrue(storage.jobs().isEmpty());
    }

    @Test void pendingCleanupSurvivesRestartAndMissingFilesAreHarmless() throws Exception {
        file("output/html_1/index.html");
        var first = storage();
        var job = first.prepare(App.builder().id(1L).build());
        assertTrue(first.jobs().isEmpty(), "Active operations must not be recovered concurrently");
        var restarted = storage();
        assertEquals(1, restarted.jobs().size());
        restarted.purge(restarted.jobs().get(0));
        assertTrue(restarted.jobs().isEmpty());
        first.release(job);
        var empty = restarted.prepare(App.builder().id(2L).build());
        restarted.purge(empty); restarted.release(empty);
    }

    @Test void invalidDeploymentPathDoesNotMoveAnyFiles() throws Exception {
        file("output/html_1/index.html"); file("important/index.html");
        assertThrows(RuntimeException.class, () -> storage().prepare(App.builder().id(1L).deployKey("../important").build()));
        assertTrue(Files.exists(temp.resolve("important/index.html")));
        assertTrue(Files.exists(temp.resolve("output/html_1/index.html")));
    }

    @Test void failureHalfwayThroughStagingRestoresAlreadyMovedFiles() throws Exception {
        file("output/html_1/index.html"); file("versions/html_1/v1/index.html");
        var storage = spy(storage());
        doThrow(new IOException("directory locked")).when(storage).move(eq(temp.resolve("versions/html_1")), any());
        assertThrows(RuntimeException.class, () -> storage.prepare(App.builder().id(1L).build()));
        assertTrue(Files.exists(temp.resolve("output/html_1/index.html")));
        assertTrue(Files.exists(temp.resolve("versions/html_1/v1/index.html")));
        assertTrue(storage.jobs().isEmpty());
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void nestedWindowsJunctionDoesNotDeleteItsDestination() throws Exception {
        file("outside/index.html"); file("output/html_1/index.html");
        Path junction = temp.resolve("output/html_1/external");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), temp.resolve("outside").toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes());
        assumeTrue(process.exitValue() == 0, "当前环境不允许创建 Windows 目录联接：" + output);
        var storage = storage();
        var job = storage.prepare(App.builder().id(1L).build());
        storage.purge(job); storage.release(job);
        assertEquals("keep", Files.readString(temp.resolve("outside/index.html")));
        assertFalse(Files.exists(temp.resolve("output/html_1")));
    }
}
