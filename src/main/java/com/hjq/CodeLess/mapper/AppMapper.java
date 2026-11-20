package com.hjq.CodeLess.mapper;

import com.mybatisflex.core.BaseMapper;
import com.hjq.CodeLess.model.entity.App;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 应用 映射层。
 *
 * @author <a href="https://github.com/Echo-love928?tab=repositories">CodeLess</a>
 */
public interface AppMapper extends BaseMapper<App> {

    @Update("""
            update app set downloadCount = downloadCount + 1, updateTime = updateTime
            where id = #{appId} and isDelete = 0
            """)
    int incrementDownloadCount(@Param("appId") Long appId);
}
