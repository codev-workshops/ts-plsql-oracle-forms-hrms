package com.hrms.auth;

/** One cell of the permission matrix; its authority string is {@code MODULE:ACTION}. */
public record Permission(Module module, Action action) {

    public String authority() {
        return module + ":" + action;
    }
}
