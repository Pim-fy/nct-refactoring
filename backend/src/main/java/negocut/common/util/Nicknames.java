package negocut.common.util;

// 판매자·입찰자의 닉네임은 일부를 가려서 보여 준다. 앞 두 글자만 남기고(한 글자면 그 글자만) 나머지는 ***로 바꾼다.
public final class Nicknames {

    private static final int VISIBLE_LENGTH = 2;

    private Nicknames() {
    }

    public static String mask(String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return "***";
        }
        return nickname.substring(0, Math.min(VISIBLE_LENGTH, nickname.length())) + "***";
    }
}
