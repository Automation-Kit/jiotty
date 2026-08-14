package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.base.Supplier;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IcloudCalendarTest {
    @Mock
    private SchedulingExecutor executor;
    @Mock
    private Supplier<CloseableHttpClient> httpClientFactory;

    @Test
    void toStringRedactsTheHrefAndTheCalendarName() {
        // A CalDAV href opens with the iCloud account id, and the name is a user-authored calendar title.
        var calendar = new IcloudCalendar("https://caldav.icloud.com/",
                                          "/1051121623/calendars/27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7/",
                                          "Family Holidays",
                                          executor,
                                          httpClientFactory);

        assertThat(calendar).asString()
                            .doesNotContain("1051121623")
                            .doesNotContain("Family Holidays")
                            .isEqualTo("IcloudCalendar{id='/10…', name='Fam…'}");
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        var calendar = new IcloudCalendar("https://caldav.icloud.com/", "/1051121623/calendars/x/", "Family Holidays", executor, httpClientFactory);

        assertThat(calendar.id()).isEqualTo("/1051121623/calendars/x/");
        assertThat(calendar.name()).isEqualTo("Family Holidays");
    }
}
