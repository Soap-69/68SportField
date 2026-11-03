package com.cardshowcase.security;

/** Login realm: each has its own form, its own DAO provider and its own attempt limiter. */
public enum Realm {
    ADMIN,
    CUSTOMER
}
