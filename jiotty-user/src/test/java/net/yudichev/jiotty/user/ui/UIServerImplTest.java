package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/// Façade-level tests for [UIServerImpl]. The substantive behaviour (option persistence, displayable throttling, SSE choreography, individual handler logic)
/// lives in [OptionRegistryImpl], [DisplayableRegistryImpl], [SseServiceImpl], and each [ApiPathHandler]; those have their own tests. This file only verifies
/// the façade contract: registration delegates to the appropriate registry. Request dispatch is exercised in [UIServerRuntimeImplTest].
@ExtendWith(MockitoExtension.class)
class UIServerImplTest {
    @Mock
    private OptionRegistry optionRegistry;
    @Mock
    private DisplayableRegistry displayableRegistry;
    @Mock
    private Closeable optionRegistration;
    @Mock
    private Closeable displayableRegistration;

    private UIServerImpl server;

    @BeforeEach
    void setUp() {
        server = new UIServerImpl(optionRegistry, displayableRegistry);
    }

    @Test
    void registerOption_delegatesToRegistry(@Mock Option<?> option) {
        when(optionRegistry.register(option)).thenAnswer(_ -> optionRegistration);
        assertThat(server.registerOption(option)).isSameAs(optionRegistration);
    }

    @Test
    void registerDisplayable_delegatesToRegistry(@Mock Displayable displayable) {
        when(displayableRegistry.register(displayable)).thenAnswer(_ -> displayableRegistration);
        assertThat(server.registerDisplayable(displayable)).isSameAs(displayableRegistration);
    }
}
