package net.yudichev.jiotty.connector.google.maps;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.maps.routing.v2.ComputeRoutesRequest;
import com.google.maps.routing.v2.ComputeRoutesResponse;
import com.google.maps.routing.v2.Location;
import com.google.maps.routing.v2.RouteTravelMode;
import com.google.maps.routing.v2.RoutesGrpc;
import com.google.maps.routing.v2.RoutingPreference;
import com.google.maps.routing.v2.Waypoint;
import com.google.protobuf.Timestamp;
import com.google.type.LatLng;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static net.yudichev.jiotty.connector.google.maps.Bindings.ApiKey;

public final class RoutesServiceImpl extends BaseLifecycleComponent implements RoutesService {
    private static final Logger logger = LoggerFactory.getLogger(RoutesServiceImpl.class);
    private static final Duration CALL_DEADLINE = Duration.ofSeconds(10);
    private final AtomicInteger requestIdGen = new AtomicInteger();
    private final String apiKey;

    private ManagedChannel channel;
    private RoutesGrpc.RoutesFutureStub stub;

    @Inject
    public RoutesServiceImpl(@ApiKey String apiKey) {
        this.apiKey = checkNotNull(apiKey);
    }

    @Override
    protected void doStart() {
        channel = NettyChannelBuilder.forAddress("routes.googleapis.com", 443).build();
        stub = RoutesGrpc.newFutureStub(ClientInterceptors.intercept(channel, new RoutesInterceptor(apiKey)));
    }

    @Override
    protected void doStop() {
        channel.shutdownNow();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Channel did not terminate in 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public CompletableFuture<Routes> computeRoutes(RouteParameters parameters) {
        return whenStartedAndNotLifecycling(() -> {

            var builder = ComputeRoutesRequest.newBuilder()
                                              .setOrigin(createWaypoint(parameters.originLocation()))
                                              .setDestination(createWaypoint(parameters.destinationLocation()))
                                              .setTravelMode(RouteTravelMode.DRIVE)
                                              .setRoutingPreference(RoutingPreference.TRAFFIC_AWARE);
            parameters.departureTime().ifPresent(departure -> builder.setDepartureTime(toProto(departure)));
            parameters.arrivalTime().ifPresent(arrival -> builder.setArrivalTime(toProto(arrival)));
            ComputeRoutesRequest request = builder.build();
            int requestId = requestIdGen.incrementAndGet();
            logger.debug("[{}] Sending request: {}", requestId, request);
            ListenableFuture<ComputeRoutesResponse> future = stub.withDeadlineAfter(CALL_DEADLINE).computeRoutes(request);
            var resultFuture = new CompletableFuture<Routes>();
            future.addListener(() -> {
                ComputeRoutesResponse response;
                try {
                    response = future.get();
                    logger.debug("[{}] Succeed: {}", requestId, response);
                    List<Route> routeList = new ArrayList<>(response.getRoutesCount());
                    for (int i = 0; i < response.getRoutesCount(); i++) {
                        routeList.add(fromProto(response.getRoutes(i)));
                    }
                    resultFuture.complete(Routes.builder()
                                                .setRoutes(routeList)
                                                .build());
                } catch (InterruptedException | ExecutionException e) {
                    logger.debug("[{}] Failed", requestId, e);
                    resultFuture.completeExceptionally(e);
                }
            }, directExecutor());
            return resultFuture;
        });
    }

    private static Waypoint.Builder createWaypoint(Either<String, LatLon> addressOrLatLon) {
        return addressOrLatLon.map(RoutesServiceImpl::createWaypointForAddress,
                                   RoutesServiceImpl::createWaypointForLatLng);
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private static Route fromProto(com.google.maps.routing.v2.Route protoRoute) {
        return Route.builder()
                    .setDistanceMetres(protoRoute.getDistanceMeters())
                    .setDuration(fromProto(protoRoute.getDuration()))
                    .build();
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private static Duration fromProto(com.google.protobuf.Duration duration) {
        return Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
    }

    private static Timestamp.Builder toProto(Instant departure) {
        return Timestamp.newBuilder().setSeconds(departure.getEpochSecond()).setNanos(departure.getNano());
    }

    public static Waypoint.Builder createWaypointForAddress(String address) {
        return Waypoint.newBuilder().setAddress(address);
    }

    public static Waypoint.Builder createWaypointForLatLng(LatLon latLon) {
        return Waypoint.newBuilder()
                       .setLocation(Location.newBuilder()
                                            .setLatLng(LatLng.newBuilder()
                                                             .setLatitude(latLon.lat())
                                                             .setLongitude(latLon.lon())));
    }

    @SuppressWarnings("ClassCanBeRecord")
    private static final class RoutesInterceptor implements ClientInterceptor {

        private static final Metadata.Key<String> API_KEY_HEADER = Metadata.Key.of("x-goog-api-key",
                                                                                   Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> FIELD_MASK_HEADER = Metadata.Key.of("x-goog-fieldmask",
                                                                                      Metadata.ASCII_STRING_MARSHALLER);
        private final String apiKey;

        public RoutesInterceptor(String apiKey) {
            this.apiKey = apiKey;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                   CallOptions callOptions, Channel next) {
            logger.trace("Intercepted {}", method.getFullMethodName());
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            call = new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(API_KEY_HEADER, apiKey);
                    // these are camel-cased field names of the Route type
                    headers.put(FIELD_MASK_HEADER, "routes.distanceMeters,routes.duration");
                    super.start(responseListener, headers);
                }
            };
            return call;
        }
    }
}
