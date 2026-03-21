package net.yudichev.jiotty.connector.icloud.calendar;

import com.github.caldav4j.CalDAVConstants;
import com.github.caldav4j.methods.CalDAV4JMethodFactory;
import com.github.caldav4j.methods.HttpPropFindMethod;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.DavException;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.property.DavProperty;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.apache.jackrabbit.webdav.property.DavPropertySet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Node;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

final class IcloudCalendarService extends BaseLifecycleComponent implements CalendarService {
    private static final Logger logger = LogManager.getLogger(IcloudCalendarService.class);

    private static final String ICLOUD_BASE = "https://caldav.icloud.com";
    private static final String URI_WELL_KNONWN_CALDAV = ICLOUD_BASE + "/.well-known/caldav";
    private static final int HTTP_TIMEOUT_MS = 10_000;

    private final ExecutorFactory executorFactory;
    private final Supplier<CloseableHttpClient> httpClientFactory;
    private SchedulingExecutor executor;

    @Inject
    public IcloudCalendarService(ExecutorFactory executorFactory,
                                 @Username String username,
                                 @Password String password) {
        this.executorFactory = checkNotNull(executorFactory);
        var credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(checkNotNull(username), checkNotNull(password)));
        httpClientFactory = () -> {
            // Apply strict timeouts to avoid indefinite hangs
            return HttpClients.custom()
                              .setDefaultCredentialsProvider(credentialsProvider)
                              .setDefaultRequestConfig(RequestConfig.custom()
                                                                    .setConnectTimeout(HTTP_TIMEOUT_MS)
                                                                    .setSocketTimeout(HTTP_TIMEOUT_MS)
                                                                    .setConnectionRequestTimeout(HTTP_TIMEOUT_MS)
                                                                    .build())
                              .evictExpiredConnections()
                              .evictIdleConnections(HTTP_TIMEOUT_MS, MILLISECONDS)
                              .build();
        };
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("Icloud-Calendar");
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, executor);
    }

    @Override
    public CompletableFuture<List<Calendar>> retrieveCalendars() {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            // Prepare HTTP BASIC Auth with app-specific password

            try (CloseableHttpClient httpClient = httpClientFactory.get()) {

                var methodFactory = new CalDAV4JMethodFactory();
                String calendarHomeUrl = getCalendarSetHomeUrl(methodFactory, httpClient);
                var davPropertyNames = new DavPropertyNameSet();
                davPropertyNames.add(DavPropertyName.create(DavConstants.PROPERTY_RESOURCETYPE, DavConstants.NAMESPACE));
                davPropertyNames.add(DavPropertyName.create(DavConstants.PROPERTY_DISPLAYNAME, DavConstants.NAMESPACE));
                HttpPropFindMethod pf = methodFactory.createPropFindMethod(calendarHomeUrl, davPropertyNames, DavConstants.DEPTH_1);
                var response = httpClient.execute(pf);

                MultiStatusResponse[] calendarResponses = pf.getResponseBodyAsMultiStatus(response).getResponses();

                var resultBuilder = ImmutableList.<Calendar>builder();
                // Collect calendar info
                for (MultiStatusResponse resp : calendarResponses) {
                    String href = resp.getHref();
                    DavPropertySet properties = resp.getProperties(200);
                    DavProperty<?> resourceTypeProp = properties.get(DavConstants.PROPERTY_RESOURCETYPE);
                    DavProperty<?> displayNameProp = properties.get(DavConstants.PROPERTY_DISPLAYNAME);
                    if (displayNameProp != null && isCalendar(resourceTypeProp)) {
                        Object displayName = displayNameProp.getValue();
                        resultBuilder.add(new IcloudCalendar(calendarHomeUrl, href, String.valueOf(displayName), executor, httpClientFactory));
                        logger.info("Retrieved calendar: {} -> {}", href, displayName);
                    } else {
                        logger.debug("Skipping non-calendar entry: {}", href);
                    }
                }
                return resultBuilder.build();
            } catch (IOException | DavException e) {
                throw new RuntimeException("Failed to fetch calendar list", e);
            }
        }));
    }

    private static boolean isCalendar(DavProperty<?> resourceTypeProp) {
        return resourceTypeProp != null
               && resourceTypeProp.getValue() instanceof Collection<?> collection
               && collection.stream().anyMatch(node -> "calendar".equals(((Node) node).getNodeName()));
    }

    private static String getCalendarSetHomeUrl(CalDAV4JMethodFactory methodFactory, HttpClient httpClient) throws IOException, DavException {
        var davPropertyNames = new DavPropertyNameSet();
        davPropertyNames.add(DavPropertyName.create("calendar-home-set", CalDAVConstants.NAMESPACE_CALDAV));
        HttpPropFindMethod pf = methodFactory.createPropFindMethod(URI_WELL_KNONWN_CALDAV, davPropertyNames, 0);
        logger.debug("Executing {}", pf);
        var response = httpClient.execute(pf);
        MultiStatus responseBodyAsMultiStatus = pf.getResponseBodyAsMultiStatus(response);
        MultiStatusResponse[] homeResponses = responseBodyAsMultiStatus.getResponses();
        checkArgument(homeResponses.length >= 1, "Response does not have any WebDav Multi-Status responses: %s", responseBodyAsMultiStatus);
        DavPropertySet propertiesForStatus200 = homeResponses[0].getProperties(200);
        checkArgument(!propertiesForStatus200.isEmpty(), "Multi-Status response %s has no properties for status 200", homeResponses[0]);
        DavProperty<?> hrefProperty = propertiesForStatus200.iterator().next();
        Object propertyValue = hrefProperty.getValue();
        checkArgument(propertyValue instanceof Node node && !Strings.isNullOrEmpty(node.getTextContent()),
                      "Invalid structure of first property if the Multi-Status response %s: must be a Node with a text content");
        return ((Node) propertyValue).getTextContent();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Username {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Password {
    }
}
