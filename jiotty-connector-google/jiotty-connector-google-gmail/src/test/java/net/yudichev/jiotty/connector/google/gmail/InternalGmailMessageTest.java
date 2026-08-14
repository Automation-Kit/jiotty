package net.yudichev.jiotty.connector.google.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class InternalGmailMessageTest {
    @Mock
    private Gmail gmail;
    @Mock
    private InternalGmailObjectFactory objectFactory;

    @Test
    void toStringRedactsAddressesAndSubjectAndKeepsTheDate() {
        var message = messageWithHeaders(header("From", "alice@example.com"),
                                         header("To", "bob@example.com"),
                                         header("Subject", "Divorce settlement"),
                                         header("Date", "Tue, 12 Aug 2026 09:15:00 +0100"));

        assertThat(new InternalGmailMessage(gmail, objectFactory, message)).asString()
                                                                           .isEqualTo("From=ali…, To=bob…, Subject=Div…, "
                                                                                      + "Date=Tue, 12 Aug 2026 09:15:00 +0100")
                                                                           .doesNotContain("alice@example.com")
                                                                           .doesNotContain("bob@example.com")
                                                                           .doesNotContain("Divorce settlement");
    }

    @Test
    void toStringOmitsHeadersOutsideTheRenderedSet() {
        var message = messageWithHeaders(header("From", "alice@example.com"), header("Received", "from mx.example.com"));

        assertThat(new InternalGmailMessage(gmail, objectFactory, message)).asString()
                                                                           .isEqualTo("From=ali…")
                                                                           .doesNotContain("mx.example.com");
    }

    @Test
    void toStringRendersAHeaderWithNoValueAsNull() {
        var message = messageWithHeaders(header("Subject", null));

        assertThat(new InternalGmailMessage(gmail, objectFactory, message)).asString().isEqualTo("Subject=null");
    }

    private static MessagePartHeader header(String name, String value) {
        return new MessagePartHeader().setName(name).setValue(value);
    }

    private static Message messageWithHeaders(MessagePartHeader... headers) {
        return new Message().setPayload(new MessagePart().setHeaders(List.of(headers)));
    }
}
