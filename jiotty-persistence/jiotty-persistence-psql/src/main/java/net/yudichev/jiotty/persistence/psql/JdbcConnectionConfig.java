package net.yudichev.jiotty.persistence.psql;

public record JdbcConnectionConfig(String url, String username, String password) {}
