package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.inject.Key;
import com.google.inject.multibindings.Multibinder;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;

public final class AuthenticatedHttpServerModule extends BaseExposedKeyModule<UIHttpServer> {
    private final BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec;
    private final BindingSpec<Integer> listenPortSpec;
    private final BindingSpec<Double> preAuthRequestsPerSecondSpec;
    private final BindingSpec<Integer> maxInFlightVerificationsSpec;
    private final BindingSpec<Boolean> trustProxyHeadersSpec;
    private final BindingSpec<Double> perUidRequestsPerSecondSpec;
    private final BindingSpec<Double> perUidBurstSpec;
    private final List<BindingSpec<ServletMount>> servletMountSpecs;

    private AuthenticatedHttpServerModule(BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec,
                                          BindingSpec<Integer> listenPortSpec,
                                          BindingSpec<Double> preAuthRequestsPerSecondSpec,
                                          BindingSpec<Integer> maxInFlightVerificationsSpec,
                                          BindingSpec<Boolean> trustProxyHeadersSpec,
                                          BindingSpec<Double> perUidRequestsPerSecondSpec,
                                          BindingSpec<Double> perUidBurstSpec,
                                          List<BindingSpec<ServletMount>> servletMountSpecs,
                                          SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.userTokenAuthoriserSpec = checkNotNull(userTokenAuthoriserSpec, "userTokenAuthoriserSpec");
        this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
        this.preAuthRequestsPerSecondSpec = checkNotNull(preAuthRequestsPerSecondSpec, "preAuthRequestsPerSecondSpec");
        this.maxInFlightVerificationsSpec = checkNotNull(maxInFlightVerificationsSpec, "maxInFlightVerificationsSpec");
        this.trustProxyHeadersSpec = checkNotNull(trustProxyHeadersSpec, "trustProxyHeadersSpec");
        this.perUidRequestsPerSecondSpec = checkNotNull(perUidRequestsPerSecondSpec, "perUidRequestsPerSecondSpec");
        this.perUidBurstSpec = checkNotNull(perUidBurstSpec, "perUidBurstSpec");
        this.servletMountSpecs = ImmutableList.copyOf(servletMountSpecs);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        userTokenAuthoriserSpec.bind(UserTokenAuthoriser.class)
                               .annotatedWith(AuthenticatedUIRequestAuthoriser.Dependency.class)
                               .installedBy(this::installLifecycleComponentModule);
        listenPortSpec.bind(int.class).annotatedWith(UIHttpServerImpl.ListenPort.class).installedBy(this::installLifecycleComponentModule);
        preAuthRequestsPerSecondSpec.bind(Double.class)
                                    .annotatedWith(PreAuthAdmissionControl.RequestsPerSecond.class)
                                    .installedBy(this::installLifecycleComponentModule);
        maxInFlightVerificationsSpec.bind(Integer.class)
                                    .annotatedWith(PreAuthAdmissionControl.MaxInFlightVerifications.class)
                                    .installedBy(this::installLifecycleComponentModule);
        trustProxyHeadersSpec.bind(Boolean.class)
                             .annotatedWith(PreAuthAdmissionControl.TrustProxyHeaders.class)
                             .installedBy(this::installLifecycleComponentModule);
        perUidRequestsPerSecondSpec.bind(Double.class)
                                   .annotatedWith(PerUidRateLimiter.RequestsPerSecond.class)
                                   .installedBy(this::installLifecycleComponentModule);
        perUidBurstSpec.bind(Double.class)
                       .annotatedWith(PerUidRateLimiter.MaxBurst.class)
                       .installedBy(this::installLifecycleComponentModule);
        bind(UIRequestAuthoriser.class).annotatedWith(ApiServletMount.Dependency.class).to(AuthenticatedUIRequestAuthoriser.class);
        Multibinder<ServletMount> mountBinder = Multibinder.newSetBinder(binder(), ServletMount.class);
        // Built-in mount: the per-user /ui/api context
        mountBinder.addBinding().to(ApiServletMount.class);
        for (BindingSpec<ServletMount> mountSpec : servletMountSpecs) {
            var mountAnnotation = uniqueAnnotation();
            mountSpec.bind(ServletMount.class)
                     .annotatedWith(mountAnnotation)
                     .installedBy(this::installLifecycleComponentModule);
            mountBinder.addBinding().to(Key.get(ServletMount.class, mountAnnotation));
        }
        bind(exposedKey).to(registerLifecycleComponent(UIHttpServerImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<UIHttpServer, Builder> {
        private final List<BindingSpec<ServletMount>> servletMountSpecs = new ArrayList<>();
        private BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec;
        private BindingSpec<Integer> listenPortSpec = BindingSpec.literally(0);
        private BindingSpec<Double> preAuthRequestsPerSecondSpec = BindingSpec.literally(10.0);
        private BindingSpec<Integer> maxInFlightVerificationsSpec = BindingSpec.literally(20);
        /// Defaults to distrusting `X-Forwarded-For`, so a directly-reachable server cannot have its per-source rate limit chosen by the caller.
        private BindingSpec<Boolean> trustProxyHeadersSpec = BindingSpec.literally(false);
        private BindingSpec<Double> perUidRequestsPerSecondSpec = BindingSpec.literally(5.0);
        /// Burst well above the sustained rate: a legitimate app start fans out a handful of requests at once, then goes quiet as its data streams over SSE.
        /// A ceiling of 20 absorbs that (and a failed-request retry or two) while the 5/s sustained rate still caps abuse.
        private BindingSpec<Double> perUidBurstSpec = BindingSpec.literally(20.0);

        public Builder setUserTokenAuthoriser(BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec) {
            this.userTokenAuthoriserSpec = checkNotNull(userTokenAuthoriserSpec, "userTokenAuthoriserSpec");
            return this;
        }

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
            return this;
        }

        public Builder withPreAuthRequestsPerSecond(BindingSpec<Double> preAuthRequestsPerSecondSpec) {
            this.preAuthRequestsPerSecondSpec = checkNotNull(preAuthRequestsPerSecondSpec, "preAuthRequestsPerSecondSpec");
            return this;
        }

        public Builder withMaxInFlightVerifications(BindingSpec<Integer> maxInFlightVerificationsSpec) {
            this.maxInFlightVerificationsSpec = checkNotNull(maxInFlightVerificationsSpec, "maxInFlightVerificationsSpec");
            return this;
        }

        /// Honour `X-Forwarded-For` when picking a request's rate-limit bucket. Set this only where a trusted proxy fronts the server and the app port is
        /// unreachable directly — otherwise a caller sets the header itself and picks its own bucket, escaping the limit.
        public Builder withTrustProxyHeaders(BindingSpec<Boolean> trustProxyHeadersSpec) {
            this.trustProxyHeadersSpec = checkNotNull(trustProxyHeadersSpec, "trustProxyHeadersSpec");
            return this;
        }

        /// How fast one authenticated user may drive the API, sustained. Bounds an authenticated account regardless of how many addresses it comes from,
        /// complementing the per-source pre-auth limit.
        public Builder withPerUidRequestsPerSecond(BindingSpec<Double> perUidRequestsPerSecondSpec) {
            this.perUidRequestsPerSecondSpec = checkNotNull(perUidRequestsPerSecondSpec, "perUidRequestsPerSecondSpec");
            return this;
        }

        /// How many requests one authenticated user may fire at once before the sustained rate applies — sized to swallow a legitimate app-startup fan-out.
        public Builder withPerUidBurst(BindingSpec<Double> perUidBurstSpec) {
            this.perUidBurstSpec = checkNotNull(perUidBurstSpec, "perUidBurstSpec");
            return this;
        }

        /// Accumulator: call once per mount. Each registration is bound under its own unique annotation and contributed to the [Multibinder] behind
        /// `Set<ServletMount>`.
        public Builder addServletMount(BindingSpec<ServletMount> servletMountSpec) {
            servletMountSpecs.add(checkNotNull(servletMountSpec, "servletMountSpec"));
            return this;
        }

        @Override
        public ExposedKeyModule<UIHttpServer> build() {
            return new AuthenticatedHttpServerModule(userTokenAuthoriserSpec,
                                                     listenPortSpec,
                                                     preAuthRequestsPerSecondSpec,
                                                     maxInFlightVerificationsSpec,
                                                     trustProxyHeadersSpec,
                                                     perUidRequestsPerSecondSpec,
                                                     perUidBurstSpec,
                                                     servletMountSpecs, specifiedAnnotation());
        }
    }
}
