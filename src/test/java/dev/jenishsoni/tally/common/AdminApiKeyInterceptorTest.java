package dev.jenishsoni.tally.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminApiKeyInterceptorTest {

    // Throwaway value for this test only.
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private final AdminApiKeyInterceptor interceptor = new AdminApiKeyInterceptor(KEY);

    @Test
    void theConfiguredKeyAsABearerTokenPasses() {
        assertThat(preHandle(interceptor, "Bearer " + KEY)).isTrue();
    }

    @Test
    void theSchemeIsCaseInsensitive() {
        assertThat(preHandle(interceptor, "bearer " + KEY)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bearer 0123456789abcdef0123456789abcdee",
            "Bearer 0123456789abcdef",
            "Bearer 0123456789abcdef0123456789abcdef0",
            "Basic 0123456789abcdef0123456789abcdef",
            "Bearer ",
            "Bearer",
            "0123456789abcdef0123456789abcdef"})
    void anyOtherAuthorizationIsRefused(String authorization) {
        assertRefused(interceptor, authorization);
    }

    @Test
    void aMissingAuthorizationHeaderIsRefused() {
        assertRefused(interceptor, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankConfiguredKeyRefusesEverything(String configuredKey) {
        AdminApiKeyInterceptor closed = new AdminApiKeyInterceptor(configuredKey);

        assertRefused(closed, null);
        assertRefused(closed, "Bearer ");
        assertRefused(closed, "Bearer " + configuredKey);
        assertRefused(closed, "Bearer " + KEY);
    }

    @Test
    void aConfiguredKeyShorterThan32CharactersStopsTheApplicationFromStarting() {
        String shortKey = KEY.substring(1);

        assertThatThrownBy(() -> new AdminApiKeyInterceptor(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters")
                .hasMessageNotContaining(shortKey);
    }

    private static void assertRefused(AdminApiKeyInterceptor interceptor, String authorization) {
        assertThatThrownBy(() -> preHandle(interceptor, authorization))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("A valid admin API key is required");
    }

    private static boolean preHandle(AdminApiKeyInterceptor interceptor, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/reconcile");
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

}
