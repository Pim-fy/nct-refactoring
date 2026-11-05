package negocut.report.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import negocut.report.entity.Report;
import negocut.report.entity.ReportStatus;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // 접수 상태인 같은 신고가 있는지. 접수 상태일 때만 active_key가 채워진다.
    boolean existsByActiveKey(String activeKey);

    // 내 신고 내역. 최근 신고가 먼저다.
    @Query(value = "select r from Report r where r.reporter.id = :reporterId order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from Report r where r.reporter.id = :reporterId")
    Page<Report> findByReporterId(Long reporterId, Pageable pageable);

    // 신고 처리: 같은 신고를 동시에 처리하지 못하도록 행을 잠그고 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Report r where r.id = :id")
    Optional<Report> findByIdForUpdate(Long id);

    // 관리자 신고 목록. 상태를 고르지 않으면 전체이고, 최근 신고가 먼저다.
    @Query(value = "select r from Report r join fetch r.reporter where (:status is null or r.reportStatus = :status)"
            + " order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from Report r where (:status is null or r.reportStatus = :status)")
    Page<Report> searchForAdmin(ReportStatus status, Pageable pageable);
}
