package com.minierp.shared;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minierp.IntegrationTest;
import com.minierp.TestTokens;
import com.minierp.TestUsers;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Bearer-token rules: who gets 401, who gets 403, what's public. */
class SecurityTests extends IntegrationTest {

    @Test
    void noTokenMeans401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() throws Exception {
        String token = TestTokens.issue(TestUsers.ADMIN, TestTokens.ISSUER, "some-other-app");
        mvc.perform(MockMvcRequestBuilders.get("/api/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        String token = TestTokens.issue(TestUsers.ADMIN, "http://evil.example/realms/erp", TestTokens.AUDIENCE);
        mvc.perform(MockMvcRequestBuilders.get("/api/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedInUserWithoutTheRoleGets403() throws Exception {
        perform("/api/products", TestUsers.SALES, Map.of("sku", "X-1", "name", "x", "baseUom", "EA", "taxRateBp", 0))
                .andExpect(status().isForbidden());
        perform("/api/warehouses", TestUsers.NOBODY, Map.of("code", "X", "name", "x")).andExpect(status().isForbidden());
    }

    @Test
    void anyoneSignedInCanRead() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/products").with(TestUsers.NOBODY.bearer())).andExpect(status().isOk());
    }

    @Test
    void theApiDescriptionIsPublicButNothingElseIs() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/somewhere-else").with(TestUsers.ADMIN.bearer())).andExpect(status().isForbidden());
    }
}
