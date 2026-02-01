package net.yudichev.jiotty.connector.google.gmail;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.api.services.gmail.GmailScopes.GMAIL_SEND;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.io.BaseEncoding.base64;
import static java.lang.Boolean.TRUE;
import static net.yudichev.jiotty.common.lang.Locks.inLock;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.connector.google.gmail.Constants.ME;

// TODO ensure it queues emails for a significant amount of time if unable to send, think internet connection interrupted
@Plugin(name = "GmailAppender",
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE)
public final class GmailAppender extends AbstractAppender {
    private final String emailAddress;
    private final Path authDataStorePath;
    private final String applicationName;
    private final URL credentialsUrl;
    private final Lock lock = new ReentrantLock();
    private Gmail service;

    @SuppressWarnings("ConstructorWithTooManyParameters")
    private GmailAppender(String name,
                          Filter filter,
                          Layout<? extends Serializable> layout,
                          boolean ignoreExceptions,
                          String emailAddress,
                          String authDataStoreRootDir,
                          String applicationName,
                          Boolean addHostNameToSubject,
                          String credentialsResourcePath,
                          String credentialsFilePath,
                          Property[] properties) {
        super(name, filter, layout, ignoreExceptions, properties);
        this.emailAddress = checkNotNull(emailAddress, "emailAddress attribute is required");
        boolean hasResourcePath = credentialsResourcePath != null;
        boolean hasFilePath = credentialsFilePath != null;
        checkArgument(!hasResourcePath || !hasFilePath,
                      "Specify either credentialsResourcePath or credentialsFilePath, not both");
        checkArgument(hasResourcePath || hasFilePath,
                      "Either credentialsResourcePath or credentialsFilePath is required");
        if (hasResourcePath) {
            credentialsUrl = GmailAppender.class.getClassLoader().getResource(credentialsResourcePath);
            checkArgument(credentialsUrl != null, "Credentials resource not found at %s", credentialsResourcePath);
        } else {
            Path credentialsPath = Paths.get(credentialsFilePath);
            checkArgument(Files.exists(credentialsPath), "Credentials file not found at %s", credentialsFilePath);
            credentialsUrl = getAsUnchecked(() -> credentialsPath.toUri().toURL());
        }
        this.applicationName = checkNotNull(applicationName, "applicationName attribute is required")
                + (TRUE.equals(addHostNameToSubject) ? " @ " + getAsUnchecked(() -> InetAddress.getLocalHost().getHostName()) : "");
        authDataStorePath = authDataStoreRootDir == null ?
                Paths.get(System.getProperty("user.home"))
                     .resolve("." + applicationName)
                     .resolve("googletokens") :
                Paths.get(authDataStoreRootDir);
    }

    @SuppressWarnings({"BooleanParameter", "MethodWithTooManyParameters"})
    @PluginFactory
    public static GmailAppender createAppender(@PluginAttribute("name") String name,
                                               @PluginAttribute("ignoreExceptions") boolean ignoreExceptions,
                                               @PluginAttribute("emailAddress") String emailAddress,
                                               @PluginAttribute("authDataStoreRootDir") String authDataStoreRootDir,
                                               @PluginAttribute("applicationName") String applicationName,
                                               @PluginAttribute("addHostNameToSubject") Boolean addHostNameToSubject,
                                               @PluginAttribute("credentialsResourcePath") String credentialsResourcePath,
                                               @PluginAttribute("credentialsFilePath") String credentialsFilePath,
                                               @PluginElement("Layout") Layout<? extends Serializable> layout,
                                               @PluginElement("Filters") Filter filter) {

        checkArgument(name != null, "No name provided for %s", GmailAppender.class.getSimpleName());

        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }
        return new GmailAppender(name,
                                 filter,
                                 layout,
                                 ignoreExceptions,
                                 emailAddress,
                                 authDataStoreRootDir,
                                 applicationName,
                                 addHostNameToSubject,
                                 credentialsResourcePath,
                                 credentialsFilePath,
                                 null);
    }

    @Override
    public void append(LogEvent event) {
        try {
            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);

            MimeMessage email = new MimeMessage(session);

            email.setFrom(new InternetAddress(emailAddress));
            email.addRecipient(RecipientType.TO, new InternetAddress(emailAddress));
            email.setSubject(applicationName + " alert: " + event.getLevel());
            email.setText(toSerializable(event).toString());

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            email.writeTo(buffer);
            byte[] bytes = buffer.toByteArray();
            String encodedEmail = base64().encode(bytes);
            Message message = new Message();
            message.setRaw(encodedEmail);
            inLock(lock, () -> {
                if (service == null) {
                    var googleAuthorization = GoogleAuthorization.builder()
                                                                 .setHttpTransport(getAsUnchecked(GoogleNetHttpTransport::newTrustedTransport))
                                                                 .setAuthDataStoreRootDir(authDataStorePath)
                                                                 .setCredentialsUrl(credentialsUrl)
                                                                 .addRequiredScope(GMAIL_SEND)
                                                                 .build();
                    service = GmailProvider.createService(googleAuthorization);
                }
                return service;
            }).users().messages().send(ME, message).execute();
        } catch (MessagingException | IOException e) {
            //noinspection CallToPrintStackTrace OK for appender
            e.printStackTrace();
        }
    }
}
