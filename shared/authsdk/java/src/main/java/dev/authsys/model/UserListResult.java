package dev.authsys.model;

import java.util.List;

/**
 * Paginated user list returned by admin list users endpoint.
 */
public class UserListResult {

    private final List<UserInfo> users;
    private final int total;
    private final int page;
    private final int limit;

    public UserListResult(List<UserInfo> users, int total, int page, int limit) {
        this.users = users;
        this.total = total;
        this.page = page;
        this.limit = limit;
    }

    public List<UserInfo> getUsers() { return users; }
    public int getTotal() { return total; }
    public int getPage() { return page; }
    public int getLimit() { return limit; }
}
