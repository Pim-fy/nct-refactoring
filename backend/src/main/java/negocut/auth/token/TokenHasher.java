package negocut.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 리프레시 토큰은 원문이 아니라 SHA-256 해시로 저장한다. DB가 유출돼도 토큰을 그대로 쓸 수 없게 하기 위해서다.
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    // 저장된 해시와 비교할 때 일치 여부를 비교 시간으로 추측할 수 없게 상수 시간 비교를 쓴다.
    public static boolean matches(String token, String storedHash) {
        return MessageDigest.isEqual(
                sha256(token).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
