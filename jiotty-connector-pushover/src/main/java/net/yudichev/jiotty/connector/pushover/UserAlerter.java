package net.yudichev.jiotty.connector.pushover;

public interface UserAlerter {
    void sendAlert(User user, MessagePriority priority, String text);

    interface User {
        String token();
    }
}
