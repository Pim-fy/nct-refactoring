package negocut.auth.dto;

import java.time.OffsetDateTime;

public record EmailCodeSendResponse(OffsetDateTime expiresAt) {
}
