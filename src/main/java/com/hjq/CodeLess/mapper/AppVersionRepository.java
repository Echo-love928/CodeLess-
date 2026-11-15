package com.hjq.CodeLess.mapper;

import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.vo.AppVersionVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AppVersionRepository {
    private final JdbcTemplate jdbc;
    public AppVersionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public App findApp(Long appId, boolean lock) {
        List<App> apps = jdbc.query("select id,userId,codeGenType,initPrompt,version,generationStatus from app where id=? and isDelete=0"
                + (lock ? " for update" : ""), (row, index) -> App.builder()
                .id(row.getLong("id")).userId(row.getLong("userId")).codeGenType(row.getString("codeGenType"))
                .initPrompt(row.getString("initPrompt")).version(row.getInt("version"))
                .generationStatus(row.getString("generationStatus")).build(), appId);
        return apps.isEmpty() ? null : apps.get(0);
    }

    private final RowMapper<AppVersionVO> versionMapper = (row, index) -> {
        var version = new AppVersionVO();
        version.setAppId(row.getLong("appId"));
        version.setVersion(row.getInt("version"));
        version.setParentVersion(row.getObject("parentVersion", Integer.class));
        version.setPrompt(row.getString("prompt"));
        version.setSource(row.getString("source"));
        version.setCreateTime(row.getTimestamp("createTime").toLocalDateTime());
        return version;
    };

    public List<AppVersionVO> list(Long appId) {
        return jdbc.query("select * from app_version where appId=? order by version desc", versionMapper, appId);
    }

    public AppVersionVO findVersion(Long appId, int version) {
        List<AppVersionVO> versions = jdbc.query("select * from app_version where appId=? and version=?", versionMapper, appId, version);
        return versions.isEmpty() ? null : versions.get(0);
    }

    public int nextVersion(Long appId) {
        Integer next = jdbc.queryForObject("select coalesce(max(version),0)+1 from app_version where appId=?", Integer.class, appId);
        if (next == null || next <= 0) throw new IllegalStateException("版本号超出范围");
        return next;
    }

    public void insert(Long appId, int version, Integer parent, String prompt, String source) {
        jdbc.update("insert into app_version(appId,version,parentVersion,prompt,source) values(?,?,?,?,?)",
                appId, version, parent, prompt, source);
    }

    public void setCurrent(Long appId, int version, boolean restored) {
        int rows = jdbc.update("update app set version=?,editTime=CURRENT_TIMESTAMP"
                + (restored ? ",generationStatus='completed',cover=null" : "") + " where id=? and isDelete=0", version, appId);
        if (rows != 1) throw new IllegalStateException("更新应用版本失败");
    }
}
