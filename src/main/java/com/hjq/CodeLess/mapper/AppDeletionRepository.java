package com.hjq.CodeLess.mapper;

import com.hjq.CodeLess.model.entity.App;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppDeletionRepository {
    private final JdbcTemplate jdbc;
    public AppDeletionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Includes the tombstone so retries and interrupted cleanup can distinguish committed deletes. */
    public App find(Long appId, boolean lock) {
        var rows = jdbc.query("select id,userId,deployKey,isDelete from app where id=?" + (lock ? " for update" : ""),
                (row, index) -> App.builder().id(row.getLong("id")).userId(row.getLong("userId"))
                        .deployKey(row.getString("deployKey")).isDelete(row.getInt("isDelete")).build(), appId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteRelatedData(Long appId) {
        jdbc.update("delete from app_version where appId=?", appId);
        int rows = jdbc.update("update app set isDelete=1,initPrompt=null,cover=null,deployKey=null,deployedTime=null,description=null,tags='[]',"
                + "visibility='private',featureStatus='none',featureRequestMessage=null,featureReviewMessage=null,featureRequestedTime=null,featureReviewedTime=null,"
                + "deployedVersion=null,version=0,deploymentStatus='offline',priority=0 where id=? and isDelete=0", appId);
        if (rows != 1) throw new IllegalStateException("删除应用记录失败");
    }
}
