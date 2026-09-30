package negocut.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import negocut.auth.entity.VerificationPurpose;

public record EmailCodeSendRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotNull VerificationPurpose purpose) {
}
