/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.who.apikey;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    private static final String HEADER = "X-API-Key";
    private static final String RAW_KEY = "who_" + "a".repeat(64);

    @Mock
    private ApiKeyCredentialRepository apiKeyCredentialRepository;

    @Mock
    private WhoService whoService;

    private ApiKeyAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(apiKeyCredentialRepository, whoService, HEADER);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noHeaderPassesThroughUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(apiKeyCredentialRepository, never()).findByKeyHash(any());
    }

    @Test
    void blankHeaderPassesThroughUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(apiKeyCredentialRepository, never()).findByKeyHash(any());
    }

    @Test
    void unrecognizedKeyPassesThroughUnauthenticated() throws Exception {
        when(apiKeyCredentialRepository.findByKeyHash(any())).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validKeyResolvesToWhoAuthenticationTokenInSecurityContext() throws Exception {
        ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "Test Key",
                ApiKeyService.sha256Hex(RAW_KEY));
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of("read:data"));

        when(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(RAW_KEY)))
                .thenReturn(Optional.of(credential));
        when(whoService.resolve(credential)).thenReturn(Optional.of(principal));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(WhoAuthenticationToken.class)
                .extracting("principal")
                .isSameAs(principal);
    }

    @Test
    void validKeyAuthoritiesMatchPermissions() throws Exception {
        ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "Test Key",
                ApiKeyService.sha256Hex(RAW_KEY));
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of("read:data", "write:data"));

        when(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(RAW_KEY)))
                .thenReturn(Optional.of(credential));
        when(whoService.resolve(credential)).thenReturn(Optional.of(principal));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("read:data", "write:data");
    }

    @Test
    void credentialFoundButWhoServiceReturnsEmptyPassesThroughUnauthenticated() throws Exception {
        ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "Test Key",
                ApiKeyService.sha256Hex(RAW_KEY));

        when(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(RAW_KEY)))
                .thenReturn(Optional.of(credential));
        when(whoService.resolve(credential)).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, RAW_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
