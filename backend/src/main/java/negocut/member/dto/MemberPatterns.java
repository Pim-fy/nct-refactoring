package negocut.member.dto;

// 회원 입력 형식. 회원가입 요청과 중복 확인 API가 같은 규칙을 쓰도록 한곳에 둔다.
public final class MemberPatterns {

    public static final String LOGIN_ID = "^[A-Za-z0-9]{6,20}$";
    public static final String LOGIN_ID_MESSAGE = "영문과 숫자로 6~20자여야 합니다.";

    public static final String NICKNAME = "^[가-힣A-Za-z0-9]{2,10}$";
    public static final String NICKNAME_MESSAGE = "한글, 영문, 숫자로 2~10자여야 합니다.";

    private MemberPatterns() {
    }
}
