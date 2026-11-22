package com.hjq.CodeLess.service;

import com.hjq.CodeLess.exception.BusinessException;
import com.hjq.CodeLess.exception.ErrorCode;
import com.hjq.CodeLess.model.dto.app.AppQueryRequest;
import com.hjq.CodeLess.model.entity.App;
import com.hjq.CodeLess.model.entity.User;
import com.hjq.CodeLess.model.enums.CodeGenTypeEnum;
import com.mybatisflex.core.paginate.Page;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AppSearchService {
    public enum Scope { PUBLIC, MINE, ADMIN }
    private static final Set<String> SORTS = Set.of("id", "appName", "createTime", "updateTime", "priority", "version");
    private static final String ACTIVE_FILTER = " where isDelete=0";
    private static final String PUBLIC_FILTER = " and visibility='public'";
    private static final String FEATURED_FILTER = " and priority=99 and featureStatus='approved'";
    private final JdbcTemplate jdbc;
    private final AppAccessPolicy access;
    public AppSearchService(JdbcTemplate jdbc, AppAccessPolicy access) { this.jdbc = jdbc; this.access = access; }

    public Page<App> search(AppQueryRequest query, User viewer, Scope scope, boolean featured) {
        return search(query, viewer, scope, featured, "*", BeanPropertyRowMapper.newInstance(App.class));
    }

    @Cacheable(value = "good_app_page",
            key = "T(com.hjq.CodeLess.utils.CacheKeyUtils).generateKey(#p0)",
            condition = "#p0 != null && #p0.pageNum <= 10")
    public Page<Long> searchFeaturedIds(AppQueryRequest query) {
        return search(query, null, Scope.PUBLIC, true, "id", (row, index) -> row.getLong("id"));
    }

    public Page<App> loadFeaturedPage(AppQueryRequest query, Page<Long> candidates) {
        List<Long> ids = candidates.getRecords();
        if (ids.isEmpty()) {
            return new Page<>(candidates.getPageNumber(), candidates.getPageSize(), candidates.getTotalRow());
        }

        // 缓存命中时从数据库重新确认应用仍可在公开精选列表展示。
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<App> apps = jdbc.query("select * from app" + ACTIVE_FILTER + PUBLIC_FILTER + FEATURED_FILTER
                        + " and id in (" + placeholders + ")", BeanPropertyRowMapper.newInstance(App.class), ids.toArray());
        if (apps.size() != ids.size()) {
            return search(query, null, Scope.PUBLIC, true);
        }
        Map<Long, App> byId = new HashMap<>();
        apps.forEach(app -> byId.put(app.getId(), app));
        return candidates.map(byId::get);
    }

    private <T> Page<T> search(AppQueryRequest query, User viewer, Scope scope, boolean featured,
                               String columns, RowMapper<T> rowMapper) {
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
        StringBuilder where = new StringBuilder(ACTIVE_FILTER);
        if (scope == Scope.PUBLIC) where.append(PUBLIC_FILTER);
        if (scope == Scope.MINE) { where.append(" and userId=?"); args.add(viewer.getId()); }
        if (featured) where.append(FEATURED_FILTER);
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
        List<T> records = jdbc.query("select " + columns + " from app" + where + " order by `" + sort + "` "
                + ("ascend".equals(direction) ? "asc" : "desc") + ",id desc limit ? offset ?",
                rowMapper, pageArgs.toArray());
        return new Page<>(records, page, size, visibleTotal);
    }

    private void like(StringBuilder sql, List<Object> args, String column, String value) {
        if (!value.isBlank()) { sql.append(" and ").append(column).append(" like ? escape '!'"); args.add(pattern(value)); }
    }
    private String pattern(String value) { return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.PARAMS_ERROR, message); }
}
