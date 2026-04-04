package net.yudichev.jiotty.user.ui.options;

public interface OptionPersistence {
    void save(Option<?> option);

    <T> void load(Option<T> option);
}
