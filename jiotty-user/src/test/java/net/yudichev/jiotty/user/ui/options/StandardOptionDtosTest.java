package net.yudichev.jiotty.user.ui.options;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.geo.LatLon;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class StandardOptionDtosTest {
    static Stream<Arguments> toStringRedactsOnlyPiiBearingValues() {
        return Stream.of(
                arguments(new StandardOptionDtos.Text("text", "userEmail", "Email address", "Account", 3, "alice@example.com"),
                          "Text[type=text, key=userEmail, label=Email address, tabName=Account, order=3, value=ali…]"),
                arguments(new StandardOptionDtos.TextArea("textarea", "notesKey", "Notes", "MainTab", 4, 5, "private notes"),
                          "TextArea[type=textarea, key=notesKey, label=Notes, tabName=MainTab, order=4, rows=5, value=pri…]"),
                arguments(new StandardOptionDtos.Checkbox("checkbox", "enabledKey", "Enabled", "MainTab", 1, true),
                          "Checkbox[type=checkbox, key=enabledKey, label=Enabled, tabName=MainTab, order=1, checked=true]"),
                arguments(new StandardOptionDtos.Time("time", "wakeTime", "Wake time", "MainTab", 2, "07:30"),
                          "Time[type=time, key=wakeTime, label=Wake time, tabName=MainTab, order=2, value=07:…]"),
                arguments(new StandardOptionDtos.Duration("duration", "chargePeriod", "Period", "MainTab", 6, "e.g. 5m", "help text", "5 minutes"),
                          "Duration[type=duration, key=chargePeriod, label=Period, tabName=MainTab, order=6, "
                          + "placeholder=e.g. 5m, help=help text, valueHuman=5 minutes]"),
                arguments(new StandardOptionDtos.Select("select", "carModel", "Car model", "MainTab", 7, List.of("Model 3", "Model Y"), "Model 3"),
                          "Select[type=select, key=carModel, label=Car model, tabName=MainTab, order=7, options=[Mod…, Mod…], value=Mod…]"),
                arguments(new StandardOptionDtos.MultiSelect("multiselect",
                                                             "drivers",
                                                             "Drivers",
                                                             "MainTab",
                                                             8,
                                                             ImmutableMap.of("id-alice", "Alice Smith", "id-bob", "Bob Jones"),
                                                             true,
                                                             List.of("id-alice")),
                          "MultiSelect[type=multiselect, key=drivers, label=Drivers, tabName=MainTab, order=8, "
                          + "allOptions={id-…=Ali…, id-…=Bob…}, allOptionsComplete=true, selectedIds=[id-…]]"),
                arguments(new StandardOptionDtos.Location("location", "homeLocation", "Home spot", "MainTab", 9, new LatLon(51.501234, -0.142567)),
                          "Location[type=location, key=homeLocation, label=Home spot, tabName=MainTab, order=9, value=~{51.5,-0.1}]"),
                arguments(new StandardOptionDtos.Location("location", "homeLocation", "Home spot", "MainTab", 9, null),
                          "Location[type=location, key=homeLocation, label=Home spot, tabName=MainTab, order=9, value=null]"));
    }

    @ParameterizedTest
    @MethodSource
    void toStringRedactsOnlyPiiBearingValues(OptionDto dto, String expected) {
        assertThat(dto.toString()).isEqualTo(expected);
    }
}
