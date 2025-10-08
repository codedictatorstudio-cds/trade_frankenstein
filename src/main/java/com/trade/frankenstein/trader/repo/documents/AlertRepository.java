package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.AlertEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRepository extends MongoRepository<AlertEntity, String> {

    Optional<AlertEntity> findByAlertId(String alertId);

    List<AlertEntity> findByAcknowledgedFalseOrderByTimestampDesc();

    List<AlertEntity> findByInstrumentKeyOrderByTimestampDesc(String instrumentKey);

    List<AlertEntity> findBySeverityAndTimestampAfterOrderByTimestampDesc(String severity, Instant since);

    List<AlertEntity> findBySeverityAndAcknowledgedFalseOrderByTimestampDesc(String severity);

    List<AlertEntity> findByTimestampAfter(Instant since);

    List<AlertEntity> findByAcknowledgedTrueAndTimestampBefore(Instant cutoff);

    @Query(value = "{'acknowledged': false, 'severity': {'$in': ['HIGH', 'CRITICAL']}}")
    List<AlertEntity> findCriticalUnacknowledgedAlerts(Pageable pageable);

    @Query(value = "{'type': ?0, 'timestamp': {'$gte': ?1}}")
    List<AlertEntity> findByTypeAndTimestampAfter(String type, Instant since);
}
