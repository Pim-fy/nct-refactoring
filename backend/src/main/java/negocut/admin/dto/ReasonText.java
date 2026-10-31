package negocut.admin.dto;

import java.util.regex.Pattern;

// 관리자 조치 사유(제한·해제)의 공백·비가시 문자 처리. 서버가 정한 규칙이며 화면(utils/format.js)도 같은 규칙으로 검사한다.
public final class ReasonText {

    // 앞뒤 공백은 NBSP·전각 공백 같은 유니코드 공백과 제로폭·서식 문자(ZWSP·BOM 등)와 한글 채움 문자·점자 빈칸도 지운다.
    private static final Pattern EDGE_SPACES = Pattern.compile("^[\\p{Z}\\s\\p{Cf}\\u3164\\u2800]+|[\\p{Z}\\s\\p{Cf}\\u3164\\u2800]+$");
    // 사람이 읽을 수 있는 글자. 공백·제어·서식 문자, 결합 문자(변형 선택자 등), 한글 채움 문자류와 점자 빈칸은 글자로 치지 않는다.
    private static final Pattern VISIBLE = Pattern.compile("[^\\p{Z}\\p{C}\\p{M}\\u3164\\u115F\\u1160\\uFFA0\\u2800]");
    // 줄바꿈과 탭을 뺀 제어 문자. NUL 같은 값이 사유로 저장되지 않게 거부한다.
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\r\\t]]");

    private ReasonText() {
    }

    public static String trim(String text) {
        return text == null ? null : EDGE_SPACES.matcher(text).replaceAll("");
    }

    // 앞뒤를 지운 사유가 읽을 수 있는 글자를 하나 이상 담고 제어 문자가 없으면 true다.
    public static boolean isMeaningful(String trimmed) {
        return trimmed != null && VISIBLE.matcher(trimmed).find() && !CONTROL.matcher(trimmed).find();
    }
}
