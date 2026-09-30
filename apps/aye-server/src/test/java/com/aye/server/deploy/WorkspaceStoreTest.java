package com.aye.server.deploy;

import com.aye.server.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStoreTest {

    @TempDir
    Path tmp;

    private final List<Database> openDbs = new ArrayList<>();

    private WorkspaceStore store() {
        Database db = new Database(tmp.resolve("data-" + openDbs.size()).toString());
        openDbs.add(db);
        return new WorkspaceStore(db);
    }

    @AfterEach
    void closeDbs() {
        openDbs.forEach(Database::close);
    }

    @Test
    void createAndGet() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("demo-project"));
        WorkspaceStore.Workspace ws = store().create(project.toString(), null, "local", null);
        assertEquals("demo-project", ws.title());
        assertEquals("local", ws.source());
        assertTrue(ws.canonicalPath().replace('\\', '/').endsWith("demo-project"));
    }

    @Test
    void duplicatePathReused() throws Exception {
        WorkspaceStore store = store();
        Path project = Files.createDirectories(tmp.resolve("p2"));
        WorkspaceStore.Workspace a = store.create(project.toString(), null, "local", null);
        WorkspaceStore.Workspace b = store.create(project.toString(), "另一个名字", "local", null);
        assertEquals(a.id(), b.id());
        assertEquals("p2", b.title(), "重复登记不改写标题");
    }

    @Test
    void missingPathRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> store().create(tmp.resolve("nope").toString(), null, "local", null));
    }

    @Test
    void renameAndDelete() throws Exception {
        WorkspaceStore store = store();
        Path project = Files.createDirectories(tmp.resolve("p3"));
        WorkspaceStore.Workspace ws = store.create(project.toString(), null, "local", null);
        store.rename(ws.id(), "新项目名");
        assertEquals("新项目名", store.get(ws.id()).title());
        assertTrue(store.delete(ws.id()));
        assertNull(store.get(ws.id()));
        assertTrue(Files.exists(project), "删除登记不碰磁盘文件");
    }

    @Test
    void sessionBinding() throws Exception {
        WorkspaceStore store = store();
        Path project = Files.createDirectories(tmp.resolve("p4"));
        WorkspaceStore.Workspace ws = store.create(project.toString(), null, "local", null);
        assertNull(store.boundWorkspace("sess-1"));
        store.bindSession("sess-1", ws.id());
        assertEquals(ws.id(), store.boundWorkspace("sess-1").id());
        store.unbindSession("sess-1");
        assertNull(store.boundWorkspace("sess-1"));
    }

    @Test
    void listOrderedByLastUsed() throws Exception {
        WorkspaceStore store = store();
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        store.create(a.toString(), null, "local", null);
        Thread.sleep(2);
        store.create(b.toString(), null, "local", null);
        var list = store.list();
        assertEquals("b", list.get(0).title());
        assertEquals("a", list.get(1).title());
    }
}
