package negocut.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 작업을 켠다. 각 스케줄러는 app.scheduler.enabled로 따로 끌 수 있다. (예: AuctionClosingScheduler)
// 여기서는 조건 없이 켜 두어, 한 스케줄러를 껐을 때 다른 스케줄러까지 같이 꺼지지 않게 한다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
