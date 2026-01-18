package net.yudichev.jiotty.user.ui;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.async.TaskExecutor;

import java.time.Duration;
import java.time.LocalTime;
import java.util.function.Function;

public enum OptionType {
    TEXT {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new TextOption(executor, (OptionMeta<String>) optionMeta) {

                @Nullable
                @Override
                public String onChanged() {
                    return (String) changeHandler.apply((O) this);
                }
            };
        }
    }, TEXT_AREA {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new TextAreaOption(executor, (OptionMeta<String>) optionMeta) {

                @Nullable
                @Override
                public String onChanged() {
                    return (String) changeHandler.apply((O) this);
                }
            };
        }
    }, CHECKBOX {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new CheckboxOption(executor, (OptionMeta<Boolean>) optionMeta) {

                @SuppressWarnings("unchecked")
                @Nullable
                @Override
                public Boolean onChanged() {
                    return (Boolean) changeHandler.apply((O) this);
                }
            };

        }
    }, TIME {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new TimeOption(executor, (OptionMeta<LocalTime>) optionMeta) {

                @Nullable
                @Override
                public LocalTime onChanged() {
                    return (LocalTime) changeHandler.apply((O) this);
                }
            };

        }
    }, DURATION {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new DurationOption(executor, (OptionMeta<Duration>) optionMeta) {

                @Nullable
                @Override
                public Duration onChanged() {
                    return (Duration) changeHandler.apply((O) this);
                }
            };
        }

    };

    abstract <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler);
}
