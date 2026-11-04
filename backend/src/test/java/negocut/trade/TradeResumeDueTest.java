package negocut.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeDueType;
import negocut.trade.entity.TradeStatus;

// 분쟁 판정으로 거래를 되돌릴 때의 기한 계산 경계 (기능 명세서 3-10 분쟁 판정). 데이터베이스 없이 엔티티만 확인한다.
class TradeResumeDueTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 12, 0, 0);

    // 낙찰 정보 없이 기한 계산만 보면 되므로 엔티티를 리플렉션으로 만들고 필요한 필드만 채운다.
    private Trade trade(LocalDateTime shipDueAt, LocalDateTime confirmDueAt, TradeStatus status) {
        try {
            Constructor<Trade> constructor = Trade.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Trade trade = constructor.newInstance();
            ReflectionTestUtils.setField(trade, "shipDueAt", shipDueAt);
            ReflectionTestUtils.setField(trade, "confirmDueAt", confirmDueAt);
            ReflectionTestUtils.setField(trade, "tradeStatus", status);
            return trade;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 정확히_2시간이_남았으면_원래_기한을_쓰고_1나노초라도_모자라면_조정한다() {
        LocalDateTime exactly = NOW.plus(Trade.RESUME_MIN_DUE);

        Trade.ResumeDue keep = trade(exactly, null, TradeStatus.DISPUTED).planResume(TradeStatus.READY, NOW);
        assertThat(keep.adjusted()).isFalse();
        assertThat(keep.resumedDueAt()).isEqualTo(exactly);
        assertThat(keep.dueType()).isEqualTo(TradeDueType.SHIP);

        Trade.ResumeDue adjust = trade(exactly.minusNanos(1), null, TradeStatus.DISPUTED).planResume(TradeStatus.READY, NOW);
        assertThat(adjust.adjusted()).isTrue();
        assertThat(adjust.resumedDueAt()).isEqualTo(exactly);
        assertThat(adjust.currentDueAt()).isEqualTo(exactly.minusNanos(1));
    }

    @Test
    void 접수_직전이_진행이면_구매_확정_기한을_보고_기한이_없거나_지났으면_복귀_시각부터_2시간이다() {
        LocalDateTime floor = NOW.plus(Duration.ofHours(2));

        Trade.ResumeDue past = trade(NOW.plusDays(5), NOW.minusDays(3), TradeStatus.DISPUTED).planResume(TradeStatus.IN_PROGRESS, NOW);
        assertThat(past.dueType()).isEqualTo(TradeDueType.CONFIRM);
        assertThat(past.adjusted()).isTrue();
        assertThat(past.resumedDueAt()).isEqualTo(floor);

        Trade.ResumeDue none = trade(NOW.plusDays(5), null, TradeStatus.DISPUTED).planResume(TradeStatus.IN_PROGRESS, NOW);
        assertThat(none.adjusted()).isTrue();
        assertThat(none.currentDueAt()).isNull();
        assertThat(none.resumedDueAt()).isEqualTo(floor);

        // 구매 확정 기한을 보므로 발송 기한은 상관없다.
        Trade.ResumeDue far = trade(NOW.minusDays(9), NOW.plusDays(4), TradeStatus.DISPUTED).planResume(TradeStatus.IN_PROGRESS, NOW);
        assertThat(far.adjusted()).isFalse();
        assertThat(far.resumedDueAt()).isEqualTo(NOW.plusDays(4));
    }

    @Test
    void 복귀하면_상태가_돌아가고_조정된_발송_기한만_미이행_표시를_지우며_분쟁_상태가_아니면_거부한다() {
        Trade ready = trade(NOW.minusHours(1), null, TradeStatus.DISPUTED);
        ready.markUnfulfilled(NOW.minusMinutes(30));
        Trade.ResumeDue due = ready.resume(TradeStatus.READY, NOW);
        assertThat(due.adjusted()).isTrue();
        assertThat(ready.getTradeStatus()).isEqualTo(TradeStatus.READY);
        assertThat(ready.getShipDueAt()).isEqualTo(NOW.plusHours(2));
        assertThat(ready.isUnfulfilled()).isFalse();
        assertThat(ready.getUnfulfilledAt()).isNull();

        // 기한이 충분하면 미이행 표시를 건드리지 않고 기한도 그대로다.
        Trade enough = trade(NOW.plusDays(2), null, TradeStatus.DISPUTED);
        enough.resume(TradeStatus.READY, NOW);
        assertThat(enough.getShipDueAt()).isEqualTo(NOW.plusDays(2));

        // 구매 확정 기한을 새로 정해도 발송 기한과 미이행 표시는 그대로다.
        Trade inProgress = trade(NOW.minusDays(9), NOW.minusHours(5), TradeStatus.DISPUTED);
        inProgress.resume(TradeStatus.IN_PROGRESS, NOW);
        assertThat(inProgress.getTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);
        assertThat(inProgress.getConfirmDueAt()).isEqualTo(NOW.plusHours(2));
        assertThat(inProgress.getShipDueAt()).isEqualTo(NOW.minusDays(9));

        Trade notDisputed = trade(NOW.plusDays(1), null, TradeStatus.IN_PROGRESS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notDisputed.resume(TradeStatus.IN_PROGRESS, NOW)).isInstanceOf(IllegalStateException.class);
        Trade badPrevious = trade(NOW.plusDays(1), null, TradeStatus.DISPUTED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> badPrevious.resume(TradeStatus.COMPLETED, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
