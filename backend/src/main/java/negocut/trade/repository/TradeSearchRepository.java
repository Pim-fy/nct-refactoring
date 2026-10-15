package negocut.trade.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeRole;
import negocut.trade.entity.TradeStatus;

// 내 거래 목록. 역할(판매자·낙찰자)과 상태 조건이 있을 때만 붙이므로 JPQL을 조립한다. 최근 거래가 먼저다. (기능 명세서 3-8)
@Repository
public class TradeSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    // role이 null이면 내가 판매자이거나 낙찰자인 거래를 모두 읽는다. status가 null이면 상태를 가리지 않는다.
    public Page<Trade> search(Long memberId, TradeRole role, TradeStatus status, Pageable pageable) {
        String party = role == TradeRole.SELLER ? "t.seller.id = :memberId"
                : role == TradeRole.BUYER ? "t.buyer.id = :memberId"
                : "(t.seller.id = :memberId or t.buyer.id = :memberId)";
        String where = " where " + party + (status != null ? " and t.tradeStatus = :status" : "");

        TypedQuery<Trade> query = entityManager.createQuery(
                "select t from Trade t join fetch t.auctionResult r join fetch r.auction a join fetch a.product "
                        + "join fetch t.seller join fetch t.buyer" + where + " order by t.createdAt desc, t.id desc", Trade.class);
        TypedQuery<Long> count = entityManager.createQuery("select count(t) from Trade t" + where, Long.class);
        query.setParameter("memberId", memberId);
        count.setParameter("memberId", memberId);
        if (status != null) {
            query.setParameter("status", status);
            count.setParameter("status", status);
        }

        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(query.getResultList(), pageable, count.getSingleResult());
    }
}
