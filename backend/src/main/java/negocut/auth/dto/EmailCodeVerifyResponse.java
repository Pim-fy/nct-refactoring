package negocut.auth.dto;

import java.time.OffsetDateTime;

public record EmailCodeVerifyResponse(String verificationToken, OffsetDateTime tokenExpiresAt) {
}
