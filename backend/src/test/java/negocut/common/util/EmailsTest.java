package negocut.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailsTest {

    @Test
    void 공백을_없애고_소문자로_바꾼다() {
        assertThat(Emails.normalize("  User@Example.COM ")).isEqualTo("user@example.com");
    }

    @Test
    void 이미_정규화된_값은_그대로다() {
        assertThat(Emails.normalize("user@example.com")).isEqualTo("user@example.com");
    }

    @Test
    void null은_null이다() {
        assertThat(Emails.normalize(null)).isNull();
    }
}
