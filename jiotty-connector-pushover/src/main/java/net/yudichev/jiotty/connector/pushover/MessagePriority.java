package net.yudichev.jiotty.connector.pushover;

public enum MessagePriority {
    LOWEST(-2), LOW(-1), NORMAL(0), HIGH(1), EMERGENCY(2);

    private final int priority;

    MessagePriority(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
