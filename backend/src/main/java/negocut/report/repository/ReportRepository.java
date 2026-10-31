package negocut.report.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.report.entity.Report;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // 접수 상태인 같은 신고가 있는지. 접수 상태일 때만 active_key가 채워진다.
    boolean existsByActiveKey(String activeKey);

    // 내 신고 내역. 최근 신고가 먼저다.
    @Query(value = "select r from Report r where r.reporter.id = :reporterId order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from Report r where r.reporter.id = :reporterId")
    Page<Report> findByReporterId(Long reporterId, Pageable pageable);
}
