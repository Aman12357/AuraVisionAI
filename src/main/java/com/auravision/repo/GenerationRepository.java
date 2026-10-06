package com.auravision.repo;

import com.auravision.model.Generation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GenerationRepository extends JpaRepository<Generation, Long> {
    List<Generation> findTop30ByOwnerTokenOrderByCreatedAtDesc(String ownerToken);
    Optional<Generation> findByIdAndOwnerToken(Long id, String ownerToken);
    List<Generation> findByStatusIn(Collection<Generation.Status> statuses);
    long countByOwnerTokenAndStatusIn(String ownerToken, Collection<Generation.Status> statuses);
    long countByOwnerTokenAndTypeAndCreatedAtAfterAndStatusNot(String ownerToken, Generation.Type type,
                                                               Instant since, Generation.Status status);
}
