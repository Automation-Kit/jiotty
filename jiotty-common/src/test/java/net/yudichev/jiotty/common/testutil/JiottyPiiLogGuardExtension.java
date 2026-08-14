package net.yudichev.jiotty.common.testutil;

import java.util.List;

/// Turns the PII log guard on for jiotty's own tests, over the tree its code logs under.
///
/// It reaches every module with this test-jar on its test classpath, including projects outside jiotty — which is why [PiiLogGuardInstaller] activates only
/// where the build sets `pii.log.guard.enabled`, set here in `jiotty-parent`'s surefire configuration and nowhere a consumer inherits.
public final class JiottyPiiLogGuardExtension extends PiiLogGuardInstaller {
    public JiottyPiiLogGuardExtension() {
        super(List.of("net.yudichev"));
    }
}
