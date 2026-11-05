package net.yudichev.jiotty.connector.webclient;

import org.htmlunit.WebClient;

public interface Script<T> {
    // by design - WebClient throws checked exceptions
    @SuppressWarnings("ProhibitedExceptionDeclared")
    T execute(WebClient webClient) throws Exception;
}
