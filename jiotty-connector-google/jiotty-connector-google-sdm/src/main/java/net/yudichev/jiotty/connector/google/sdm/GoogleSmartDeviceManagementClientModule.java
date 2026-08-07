package net.yudichev.jiotty.connector.google.sdm;

import com.google.api.services.smartdevicemanagement.v1.SmartDeviceManagement;
import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import net.yudichev.jiotty.connector.google.common.impl.BaseGoogleServiceModule;

import static com.google.common.base.Preconditions.checkNotNull;

public final class GoogleSmartDeviceManagementClientModule extends BaseGoogleServiceModule<GoogleSmartDeviceManagementClient> {
    private final BindingSpec<String> projectId;

    private GoogleSmartDeviceManagementClientModule(BindingSpec<GoogleAuthorization> googleAuthorizationSpec,
                                                    BindingSpec<String> projectId,
                                                    SpecifiedAnnotation specifiedAnnotation) {
        super(googleAuthorizationSpec, specifiedAnnotation);
        this.projectId = checkNotNull(projectId, "projectId is required");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void doConfigure() {
        projectId.bind(String.class)
                 .annotatedWith(GoogleSmartDeviceManagementClientImpl.ProjectId.class)
                 .installedBy(this::installLifecycleComponentModule);
        bind(SmartDeviceManagement.class).annotatedWith(GoogleSmartDeviceManagementClientImpl.Dependency.class)
                                         .toProvider(SmartDeviceManagementProvider.class).in(Singleton.class);
        bind(exposedKey).to(GoogleSmartDeviceManagementClientImpl.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseBuilder<GoogleSmartDeviceManagementClient, Builder> {
        private BindingSpec<String> projectIdSpec;

        public Builder setProjectId(BindingSpec<String> projectIdSpec) {
            this.projectIdSpec = checkNotNull(projectIdSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<GoogleSmartDeviceManagementClient> build() {
            return new GoogleSmartDeviceManagementClientModule(getAuthorizationSpec(), projectIdSpec, specifiedAnnotation());
        }

        @Override
        protected Builder thisBuilder() {
            return this;
        }
    }
}
