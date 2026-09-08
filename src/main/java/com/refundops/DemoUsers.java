package com.refundops;

import java.util.List;
import org.springframework.http.HttpStatus;

public final class DemoUsers {
    private static final User DAHNESH = new User("dahnesh", "Dahnesh", List.of("REQUESTOR", "APPROVER"));
    private static final User SHWETA = new User("shweta", "Shweta", List.of("REQUESTOR"));

    private DemoUsers() {
    }

    public static User get(String username) {
        if (DAHNESH.username.equals(username)) {
            return DAHNESH;
        }
        if (SHWETA.username.equals(username)) {
            return SHWETA;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Unknown demo user.");
    }

    public static final class User {
        public final String username;
        public final String displayName;
        public final List<String> roles;

        private User(String username, String displayName, List<String> roles) {
            this.username = username;
            this.displayName = displayName;
            this.roles = List.copyOf(roles);
        }
    }
}
