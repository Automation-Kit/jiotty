package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.user.ui.options.CheckboxOption;
import net.yudichev.jiotty.user.ui.options.DurationOption;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.TextAreaOption;
import net.yudichev.jiotty.user.ui.options.TextOption;
import net.yudichev.jiotty.user.ui.options.TimeOption;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.util.function.Function;

public enum OptionType {
    TEXT {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new TextOption(executor, (OptionMeta<String>) optionMeta) {

                @Override
                public @Nullable String onChanged() {
                    return (String) changeHandler.apply((O) this);
                }
            };
        }
    }, TEXT_AREA {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new TextAreaOption(executor, (OptionMeta<String>) optionMeta) {

                @Override
                public @Nullable String onChanged() {
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
                @Override
                public @Nullable Boolean onChanged() {
                    return (Boolean) changeHandler.apply((O) this);
                }
            };

        }
    }, TIME {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new TimeOption(executor, (OptionMeta<LocalTime>) optionMeta) {

                @Override
                public @Nullable LocalTime onChanged() {
                    return (LocalTime) changeHandler.apply((O) this);
                }
            };

        }
    }, DURATION {
        @SuppressWarnings("unchecked")
        @Override
        <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler) {
            return (O) new DurationOption(executor, (OptionMeta<Duration>) optionMeta) {

                @Override
                public @Nullable Duration onChanged() {
                    return (Duration) changeHandler.apply((O) this);
                }
            };
        }

    };

    abstract <T, O extends Option<T>> O createInstance(OptionMeta<T> optionMeta, TaskExecutor executor, Function<O, T> changeHandler);
}
