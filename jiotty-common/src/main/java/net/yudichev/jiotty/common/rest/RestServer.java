package net.yudichev.jiotty.common.rest;

import io.javalin.http.Handler;

public interface RestServer {
    void post(String url, Handler handler);

    void get(String path, Handler handler);

    int port();
}
