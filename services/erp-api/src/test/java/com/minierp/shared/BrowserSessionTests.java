package com.minierp.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestUsers;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The web app's way in: a server-side session after a Keycloak sign-in (the API as
 * backend-for-frontend), with CSRF protection on everything that changes data.
 */
class BrowserSessionTests extends IntegrationTest {

    @Autowired
    ClientRegistrationRepository registrations;

    @Test
    void meTellsTheWebAppWhoIsSignedIn() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/me").with(TestUsers.WAREHOUSE.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("walt"))
                .andExpect(jsonPath("$.displayName").value("walt Example"))
                .andExpect(jsonPath("$.roles[0]").value("WAREHOUSE"));
    }

    @Test
    void withoutASessionApiCallsGet401NotARedirect() throws Exception {
        // fetch() would follow a redirect to Keycloak invisibly; a 401 lets the SPA start a sign-in itself.
        mvc.perform(MockMvcRequestBuilders.get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangingRequestsNeedTheCsrfToken() throws Exception {
        Map<String, Object> body = Map.of("code", unique("W"), "name", "Via the browser");

        mvc.perform(MockMvcRequestBuilders.post("/api/warehouses").with(TestUsers.ADMIN.session())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        mvc.perform(MockMvcRequestBuilders.post("/api/warehouses").with(TestUsers.ADMIN.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void rolesFromTheSessionAreEnforcedLikeTokenRoles() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/warehouses").with(TestUsers.SALES.session()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", unique("W"), "name", "No"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void signInStartsTheCodeFlowWithPkceAgainstKeycloak() throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        String location = URLDecoder.decode(response.getRedirectedUrl(), StandardCharsets.UTF_8);

        assertThat(location)
                .startsWith("http://localhost:8880/realms/erp/protocol/openid-connect/auth?")
                .contains("client_id=erp-web", "response_type=code", "code_challenge_method=S256", "code_challenge=",
                        "redirect_uri=http://localhost:5780/login/oauth2/code/keycloak", "scope=openid profile email");
    }

    @Test
    void signOutReturnsKeycloaksLogoutUrlForTheSpaToVisit() throws Exception {
        var keycloak = registrations.findByRegistrationId("keycloak");
        mvc.perform(MockMvcRequestBuilders.post("/logout")
                        .with(TestUsers.FINANCE.session().clientRegistration(keycloak))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoutUrl").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("http://localhost:8880/realms/erp/protocol/openid-connect/logout?"),
                        org.hamcrest.Matchers.containsString("id_token_hint="),
                        org.hamcrest.Matchers.containsString("post_logout_redirect_uri=http://localhost:5780/"))));
    }

    @Test
    void downloadsWorkWithTheSessionCookie() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/reports/stock.xlsx").with(TestUsers.FINANCE.session()))
                .andExpect(status().isOk());
    }
}
