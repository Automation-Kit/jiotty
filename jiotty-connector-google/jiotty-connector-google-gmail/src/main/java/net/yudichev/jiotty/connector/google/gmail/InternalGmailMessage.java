package net.yudichev.jiotty.connector.google.gmail;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.connector.google.gmail.Bindings.GmailService;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toList;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;
import static net.yudichev.jiotty.connector.google.gmail.Constants.ME;

final class InternalGmailMessage implements GmailMessage, StringFormattable {
    private static final String DATE_HEADER = "Date";
    private static final Set<String> TO_STRING_HEADERS = ImmutableSet.of("From", "To", "Subject", DATE_HEADER);
    private final Gmail gmail;
    private final InternalGmailObjectFactory internalGmailObjectFactory;
    private final Message message;

    @Inject
    InternalGmailMessage(@GmailService Gmail gmail,
                         InternalGmailObjectFactory internalGmailObjectFactory,
                         @Assisted Message message) {
        this.gmail = checkNotNull(gmail);
        this.internalGmailObjectFactory = checkNotNull(internalGmailObjectFactory);
        this.message = checkNotNull(message);
    }

    @Override
    public Optional<String> getHeader(String name) {
        return message.getPayload().getHeaders().stream()
                      .filter(messagePartHeader -> messagePartHeader.getName().equals(name))
                      .map(MessagePartHeader::getValue)
                      .findFirst();

    }

    @Override
    public Collection<GmailMessageAttachment> getAttachments(Predicate<? super String> mimeTypePredicate) {
        return message.getPayload().getParts().stream()
                      .filter(messagePart -> mimeTypePredicate.test(messagePart.getMimeType()))
                      .map(messagePart -> internalGmailObjectFactory.createAttachment(message, messagePart))
                      .collect(toImmutableList());
    }

    @Override
    public CompletableFuture<Void> applyLabels(LabelsChange labelsChange) {
        return supplyAsync(() -> {
            asUnchecked(() -> gmail.users().messages().modify(ME, message.getId(),
                                                              new ModifyMessageRequest()
                                                                      .setAddLabelIds(toListOfIds(labelsChange.getLabelsToAdd()))
                                                                      .setRemoveLabelIds(toListOfIds(labelsChange.getLabelsToRemove())))
                                   .execute()
            );
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> applyLabels(LabelsChangeNames labelsChange) {
        return listLabels().thenCompose(gmailLabels -> {
            List<GmailLabel> labelsToRemove = labelsChange.getLabelsToRemove().stream()
                                                          .map(labelName -> gmailLabels.stream()
                                                                                       .filter(gmailLabel -> gmailLabel.getName().equals(labelName))
                                                                                       .findFirst())
                                                          .filter(Optional::isPresent)
                                                          .map(Optional::get)
                                                          .collect(toImmutableList());
            return labelsChange.getLabelsToAdd().stream()
                               .map(labelName -> gmailLabels.stream()
                                                            .filter(gmailLabel -> gmailLabel.getName().equals(labelName))
                                                            .findFirst()
                                                            .map(CompletableFuture::completedFuture)
                                                            .orElseGet(() -> createLabel(labelName)))
                               .collect(toFutureOfList())
                               .thenCompose(labelsToAdd ->
                                                    applyLabels(LabelsChange.builder()
                                                                            .setLabelsToAdd(labelsToAdd)
                                                                            .setLabelsToRemove(labelsToRemove)
                                                                            .build()));
        });
    }

    @Override
    public String toString() {
        return toString(128);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable,
                  Iterables.filter(message.getPayload().getHeaders(), header -> header != null && TO_STRING_HEADERS.contains(header.getName())),
                  "",
                  ", ",
                  "",
                  InternalGmailMessage::appendHeader);
    }

    /// {@value #DATE_HEADER} is not personal data and renders whole; `From`, `To` and `Subject` are an address, an address and message content, so
    /// they render redacted.
    private static void appendHeader(Appendable appendable, MessagePartHeader header) {
        Append.to(appendable, header.getName());
        Append.to(appendable, '=');
        if (DATE_HEADER.equals(header.getName())) {
            Append.to(appendable, header.getValue());
        } else {
            appendRedacted(appendable, header.getValue());
        }
    }

    private static List<String> toListOfIds(Collection<GmailLabel> labelsToAdd) {
        return labelsToAdd.stream().map(gmailLabel -> ((InternalGmailLabel) gmailLabel).getId()).collect(toList());
    }

    private CompletableFuture<Collection<GmailLabel>> listLabels() {
        return supplyAsync(() -> getAsUnchecked(() -> gmail.users().labels().list(ME).execute()))
                .thenApply(listLabelsResponse -> listLabelsResponse.getLabels().stream()
                                                                   .map(InternalGmailLabel::new)
                                                                   .collect(toImmutableList()));
    }

    private CompletableFuture<GmailLabel> createLabel(String labelName) {
        return supplyAsync(() -> getAsUnchecked(() -> gmail.users().labels().create(
                                                                   ME,
                                                                   new Label()
                                                                           .setName(labelName)
                                                                           .setLabelListVisibility("labelShow")
                                                                           .setMessageListVisibility("show"))
                                                           .execute()))
                .thenApply(InternalGmailLabel::new);
    }
}

