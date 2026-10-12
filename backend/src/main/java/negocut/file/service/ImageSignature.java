package negocut.file.service;

import java.util.Locale;
import java.util.Set;

// 올린 파일이 실제로 허용하는 이미지(JPEG, PNG, WebP)인지 파일 앞부분의 시그니처(매직 넘버)로 판별한다.
// 확장자와 Content-Type은 올리는 쪽이 마음대로 정할 수 있어서, 시그니처를 기준으로 삼고 두 값이 그와 맞는지 함께 확인한다.
public enum ImageSignature {

    JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
    PNG("image/png", "png", Set.of("png")),
    WEBP("image/webp", "webp", Set.of("webp"));

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final String contentType;
    private final String storedExtension;
    private final Set<String> allowedExtensions;

    ImageSignature(String contentType, String storedExtension, Set<String> allowedExtensions) {
        this.contentType = contentType;
        this.storedExtension = storedExtension;
        this.allowedExtensions = allowedExtensions;
    }

    public String contentType() {
        return contentType;
    }

    // 디스크에 저장할 때 붙이는 확장자. 올린 파일명의 확장자가 아니라 판별한 형식을 따른다.
    public String storedExtension() {
        return storedExtension;
    }

    // 이 형식에서 허용하는 파일명 확장자인지(대소문자 구분 없음)
    public boolean allowsExtension(String extension) {
        return extension != null && allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
    }

    // 시그니처로 형식을 판별한다. 허용하는 형식이 아니면 null이다.
    public static ImageSignature detect(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return PNG;
        }
        // WebP는 "RIFF" + 4바이트 크기 + "WEBP"
        if (bytes.length >= 12 && startsWith(bytes, new byte[] {'R', 'I', 'F', 'F'})
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
