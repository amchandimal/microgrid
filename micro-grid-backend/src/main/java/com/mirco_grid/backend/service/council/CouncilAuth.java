package com.mirco_grid.backend.service.council;

import com.mirco_grid.backend.service.council.CouncilApi.LoginResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Demo authentication for the council view.
 *
 * <p>One fixed account and one fixed token, deliberately. The dashboard is
 * behind a login because the data on it is not for the public - suburb-level
 * disadvantage rankings and intervention lists - not because there is a user
 * directory to authenticate against. Everything here is the smallest thing
 * that puts the door in the right place; wiring Spring Security to an identity
 * provider is a swap of this one class and the interceptor beside it.
 *
 * <p>The credentials are properties with defaults rather than literals, so a
 * deployment can set {@code council.auth.password} without a rebuild.
 */
@Service
public class CouncilAuth {

    public static final String ROLE = "COUNCIL";

    private final String username;
    private final String password;
    private final String token;
    private final String displayName;

    public CouncilAuth(
            @Value("${council.auth.username:council}") String username,
            @Value("${council.auth.password:Wollongong2026!}") String password,
            @Value("${council.auth.token:council-demo-token}") String token,
            @Value("${council.auth.display-name:Wollongong City Council}") String displayName) {
        this.username = username;
        this.password = password;
        this.token = token;
        this.displayName = displayName;
    }

    /** @return the session, or empty when either credential is wrong */
    public Optional<LoginResponse> login(String suppliedUser, String suppliedPassword) {
        if (suppliedUser == null || suppliedPassword == null) {
            return Optional.empty();
        }
        // Both are compared, and both in constant time, so a wrong username
        // and a wrong password are indistinguishable from the outside.
        boolean userOk = constantTimeEquals(username, suppliedUser.trim());
        boolean passwordOk = constantTimeEquals(password, suppliedPassword);
        if (!(userOk && passwordOk)) {
            return Optional.empty();
        }
        return Optional.of(new LoginResponse(token, ROLE, displayName));
    }

    /** Is this the {@code Authorization} header of a signed-in council user? */
    public boolean isAuthorised(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        String prefix = "Bearer ";
        if (!authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        return constantTimeEquals(token, authorizationHeader.substring(prefix.length()).trim());
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
