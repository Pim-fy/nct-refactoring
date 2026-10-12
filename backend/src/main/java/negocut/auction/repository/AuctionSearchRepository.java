package negocut.auction.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionSort;
import negocut.auction.entity.AuctionStatus;

// 경매 목록 검색. 카테고리·검색어 조건이 있을 때만 붙이므로 JPQL을 조립한다. (기능 명세서 3-4)
// 중단된 경매는 목록에 노출하지 않는다.
@Repository
public class AuctionSearchRepository {

    private static final String IN_PROGRESS = "negocut.auction.entity.AuctionStatus.IN_PROGRESS";
    // 진행 중인 경매를 먼저 보이고, 그 안에서 정렬 기준을 적용한다.
    private static final String PROGRESS_FIRST = "case when a.auctionStatus = " + IN_PROGRESS + " then 0 else 1 end";

    @PersistenceContext
    private EntityManager entityManager;

    // keywordPattern은 LIKE에 그대로 쓸 수 있는 값(앞뒤 %, 특수문자는 !로 이스케이프, 소문자)이고 없으면 null이다.
    public Page<Auction> search(Long categoryId, String keywordPattern, AuctionSort sort, Pageable pageable) {
        String where = " where a.auctionStatus <> :stopped"
                + (categoryId != null ? " and c.id = :categoryId" : "")
                + (keywordPattern != null
                        ? " and (lower(p.name) like :pattern escape '!' or lower(p.description) like :pattern escape '!')"
                        : "");

        TypedQuery<Auction> query = entityManager.createQuery(
                "select a from Auction a join fetch a.product p join fetch p.category c" + where + " order by " + orderBy(sort),
                Auction.class);
        TypedQuery<Long> count = entityManager.createQuery(
                "select count(a) from Auction a join a.product p join p.category c" + where, Long.class);
        for (TypedQuery<?> q : List.of(query, count)) {
            q.setParameter("stopped", AuctionStatus.STOPPED);
            if (categoryId != null) {
                q.setParameter("categoryId", categoryId);
            }
            if (keywordPattern != null) {
                q.setParameter("pattern", keywordPattern);
            }
        }

        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(query.getResultList(), pageable, count.getSingleResult());
    }

    private String orderBy(AuctionSort sort) {
        return switch (sort) {
            // 진행 중은 마감이 가까운 순, 종료된 경매는 최근에 끝난 순이다.
            case END_AT -> PROGRESS_FIRST + ", case when a.auctionStatus = " + IN_PROGRESS + " then a.endAt end asc, a.endAt desc, a.id desc";
            case CREATED_AT -> PROGRESS_FIRST + ", a.createdAt desc, a.id desc";
            // 입찰이 없으면 시작가를 현재가로 본다.
            case PRICE -> PROGRESS_FIRST + ", coalesce(a.currentPrice, a.startPrice) asc, a.id desc";
            case BID_COUNT -> PROGRESS_FIRST + ", a.bidCount desc, a.id desc";
        };
    }
}
