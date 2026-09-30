package negocut;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class NegocutApplication {

	// 서버 타임존을 KST로 고정. LocalDateTime은 타임존 정보를 갖지 않아
	// 배포 환경의 시스템 타임존에 의존하므로, 어디에 배포되든 KST로 기록·동작하게 미리 고정한다.
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
	}

	public static void main(String[] args) {
		SpringApplication.run(NegocutApplication.class, args);
	}

}
