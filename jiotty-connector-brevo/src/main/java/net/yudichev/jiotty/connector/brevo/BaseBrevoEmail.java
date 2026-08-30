package net.yudichev.jiotty.connector.brevo;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Optional;

/// One fully-rendered message to one recipient.
///
/// The recipient's address and name are [Value.Redacted] so that a value accidentally logged, or embedded in an exception message, renders as the style's mask
/// rather than the address itself — where the sender fields are our own published identity rather than personal data, and are left plain.
@Value.Immutable
@PublicImmutablesStyle
public interface BaseBrevoEmail {
    String senderName();

    String senderAddress();

    @Value.Redacted
    String recipientAddress();

    @Value.Redacted
    Optional<String> recipientName();

    String subject();

    /// The HTML alternative. Every value interpolated into it must already be escaped by the caller — this client does no escaping of its own.
    String htmlContent();

    /// The plain-text alternative, sent alongside the HTML. Always supplied: a security message that renders as an empty body in a text-only client is worse
    /// than one that never arrived, and its presence also improves how spam filters score the message.
    String textContent();
}
