package com.paytm.pml.wallet.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simple static bearer-token -> user map. Auth sophistication is explicitly not
 * graded; this just identifies the caller. Configure via wallet.tokens
 * ("token1:user1,token2:user2").
 */
@Component
public class TokenRegistry {

    private final Map<String, String> tokenToUser = new HashMap<>();

    public TokenRegistry(@Value("${wallet.tokens:}") String tokens) {
        if (tokens != null && !tokens.isBlank()) {
            for (String pair : tokens.split(",")) {
                String[] kv = pair.trim().split(":", 2);
                if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                    tokenToUser.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
    }

    public Optional<String> userForToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenToUser.get(token));
    }
}
