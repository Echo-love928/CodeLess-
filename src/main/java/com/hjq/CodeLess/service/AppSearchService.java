package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.dto.app.AppQueryRequest;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.mybatisflex.core.paginate.Page;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AppSearchService {
    public enum Scope { PUBLIC, MINE, ADMIN }
    private static final Set<String> SORTS = Set.of("id", "appName", "createTime", "updateTime", "priority", "version");
    private final JdbcTemplate jdbc;
    private final AppAccessPolicy access;
    public AppSearchService(JdbcTemplate jdbc, AppAccessPolicy access) { this.jdbc = jdbc; this.access = access; }

    public Page<App> search(AppQueryRequest query, User viewer, Scope scope, boolean featured) {
        if (query == null) throw invalid("请求不能为空");
        if (scope == Scope.ADMIN && !access.isAdmin(viewer)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        if (scope == Scope.MINE && viewer == null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        int page = query.getPageNum(), size = query.getPageSize();
        if (page < 1 || size < 1 || size > (scope == Scope.ADMIN ? 100 : 20)) throw invalid("页码或每页数量无效");
        long offset = (long) (page - 1) * size;
        if (scope == Scope.PUBLIC && offset >= 200) throw invalid("公开应用检索最多展示前 200 条，请缩小搜索范围");
        String sort = query.getSortField() == null || query.getSortField().isBlank() ? "createTime" : query.getSortField();
        if (!SORTS.contains(sort)) throw invalid("不支持的排序字段");
        String direction = query.getSortOrder();
        if (direction != null && !Set.of("ascend", "descend").contains(direction)) throw invalid("不支持的排序方向");
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where isDelete=0");
        if (scope == Scope.PUBLIC) where.append(" and visibility='public'");
        if (scope == Scope.MINE) { where.append(" and userId=?"); args.add(viewer.getId()); }
        if (featured) where.append(" and priority=99 and featureStatus='approved'");
        if (query.getPriority() != null) { where.append(" and priority=?"); args.add(query.getPriority()); }
        if (query.getId() != null) {
            if (query.getId() <= 0) throw invalid("应用 ID 无效");
            where.append(" and id=?"); args.add(query.getId());
        }
        if (scope != Scope.MINE && query.getUserId() != null) { where.append(" and userId=?"); args.add(query.getUserId()); }
        if (query.getVisibility() != null && !query.getVisibility().isBlank()) { where.append(" and visibility=?"); args.add(AppMetadataRules.visibility(query.getVisibility())); }
        if (scope == Scope.ADMIN && query.getFeatureStatus() != null && !query.getFeatureStatus().isBlank()) {
            if (!Set.of("none", "pending", "approved", "rejected").contains(query.getFeatureStatus())) throw invalid("精选状态无效");
            where.append(" and featureStatus=?"); args.add(query.getFeatureStatus());
        }
        if (query.getCodeGenType() != null && !query.getCodeGenType().isBlank()) {
            if (CodeGenTypeEnum.getEnumByValue(query.getCodeGenType()) == null) throw invalid("生成类型无效");
            where.append(" and codeGenType=?"); args.add(query.getCodeGenType());
        }
        like(where, args, "appName", AppMetadataRules.text(query.getAppName(), 100, "名称"));
        like(where, args, "description", AppMetadataRules.text(query.getDescription(), 500, "描述"));
        String text = AppMetadataRules.text(query.getSearchText(), 100, "搜索关键词");
        if (!text.isBlank()) {
            where.append(" and (appName like ? escape '!' or description like ? escape '!' or tags like ? escape '!')");
            for (int i = 0; i < 3; i++) args.add(pattern(text));
        }
        if (query.getTag() != null && !query.getTag().isBlank()) {
            String tag = AppMetadataRules.tags(List.of(query.getTag())).get(0);
            where.append(" and locate(?,tags)>0"); args.add("\"" + tag + "\"");
        }
        // Never expose a hidden prompt as a searchable public field or a Boolean oracle.
        if (scope != Scope.PUBLIC) like(where, args, "initPrompt", AppMetadataRules.text(query.getInitPrompt(), 100, "提示词搜索"));
        Long total = jdbc.queryForObject("select count(*) from app" + where, Long.class, args.toArray());
        long visibleTotal = scope == Scope.PUBLIC ? Math.min(total == null ? 0 : total, 200) : total == null ? 0 : total;
        int limit = scope == Scope.PUBLIC ? (int) Math.min(size, 200 - offset) : size;
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(limit); pageArgs.add(offset);
        List<App> apps = jdbc.query("select * from app" + where + " order by `" + sort + "` "
                + ("ascend".equals(direction) ? "asc" : "desc") + ",id desc limit ? offset ?",
                BeanPropertyRowMapper.newInstance(App.class), pageArgs.toArray());
        Page<App> result = new Page<>(page, size, visibleTotal); result.setRecords(apps); return result;
    }

    private void like(StringBuilder sql, List<Object> args, String column, String value) {
        if (!value.isBlank()) { sql.append(" and ").append(column).append(" like ? escape '!'"); args.add(pattern(value)); }
    }
    private String pattern(String value) { return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.PARAMS_ERROR, message); }
}
